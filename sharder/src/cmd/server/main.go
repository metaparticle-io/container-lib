package main

import (
	"fmt"
	"net/http"
	"net/http/httputil"
	"os"
	"regexp"
	"sharder"

	"k8s.io/client-go/1.4/kubernetes"
	"k8s.io/client-go/1.4/rest"

	"github.com/golang/glog"
	"github.com/spf13/pflag"
)

var (
	addresses           = pflag.StringSlice("addresses", []string{}, "The list of addresses to shard to")
	pathShardExpr       = pflag.String("path-shard-expression", "", "The path sharding expression, the first group will be used for sharding, if empty, the entire path is used.")
	address             = pflag.String("address", "localhost:8080", "The <host>:<port> to serve on")
	kubernetesService   = pflag.String("kubernetes-service", "", "If not empty, the <namespace>/<name> of a Kubernetes service to shard to.  If <namespace> is absent, 'default' is assumed.")
	kubernetesNamespace = pflag.String("kubernetes-namespace", "default", "The namespace of the kubernetes service, only used if --kubernetes-service is set.")
)

func main() {
	pflag.Parse()

	pathShardRegexp := regexp.MustCompile(*pathShardExpr)

	var serverAddresses []string
	if len(*addresses) > 0 {
		serverAddresses = *addresses
	} else if len(*kubernetesService) > 0 {
		config, err := rest.InClusterConfig()
		if err != nil {
			glog.Errorf("Error contacting server: %v", err)
			os.Exit(1)
		}
		// creates the clientset
		clientset, err := kubernetes.NewForConfig(config)
		if err != nil {
			glog.Errorf("Error contacting server: %v", err)
			os.Exit(1)
		}
		endpoints, err := clientset.Core().Endpoints(*kubernetesNamespace).Get(*kubernetesService)
		if err != nil {
			glog.Errorf("Error contacting server: %v", err)
			os.Exit(1)
		}
		for ix := range endpoints.Subsets {
			subset := &endpoints.Subsets[ix]
			for jx := range subset.Addresses {
				serverAddresses = append(serverAddresses, fmt.Sprintf("http://%s:%d", subset.Addresses[jx].IP, subset.Ports[0].Port))
			}
		}
	} else {
		fmt.Printf("Either --addresses or --kubernetes-service are required.\n")
		os.Exit(1)
	}

	glog.Infof("Sharder starting, spreading load to %v", serverAddresses)

	s := &sharder.Sharder{
		PathRE:    pathShardRegexp,
		Addresses: serverAddresses,
	}

	proxy := &httputil.ReverseProxy{
		Director: s.DirectorFn,
	}

	http.Handle("/", proxy)
	http.ListenAndServe(*address, nil)
}
