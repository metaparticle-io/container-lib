package main

import (
	"fmt"
	"net/http"
	"net/http/httputil"
	"os"
	"path"
	"regexp"
	"time"

	"k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"
	"k8s.io/client-go/tools/clientcmd"

	"github.com/golang/glog"
	"github.com/metaparticle-io/container-lib/sharder/src/sharder"
	"github.com/spf13/pflag"
)

var (
	addresses           = pflag.StringSlice("addresses", []string{}, "The list of addresses to shard to")
	pathShardExpr       = pflag.String("path-shard-expression", "", "The path sharding expression, the first group will be used for sharding, if empty, the entire path is used.")
	address             = pflag.String("address", "localhost:8080", "The <host>:<port> to serve on")
	kubernetesService   = pflag.String("kubernetes-service", "", "If not empty, the <namespace>/<name> of a Kubernetes service to shard to.  If <namespace> is absent, 'default' is assumed.")
	kubernetesNamespace = pflag.String("kubernetes-namespace", "default", "The namespace of the kubernetes service, only used if --kubernetes-service is set.")
	kubeconfig          = pflag.String("kubeconfig", path.Join(os.Getenv("HOME"), ".kube/config"), "absolute path to the kubeconfig file")
	inKubeCluster       = pflag.Bool("in-cluster-kubernetes-config", true, "If true, the default, use the in cluster kubernetes configuration.")
)

// TODO: this is duplicated, refactor to a common location
func getKubernetesAddresses(clientset *kubernetes.Clientset) ([]string, error) {
	endpoints, err := clientset.Core().Endpoints(*kubernetesNamespace).Get(*kubernetesService, v1.GetOptions{})
	if err != nil {
		return nil, err
	}
	result := []string{}
	for ix := range endpoints.Subsets {
		subset := &endpoints.Subsets[ix]
		for jx := range subset.Addresses {
			result = append(result, fmt.Sprintf("http://%s:%d", subset.Addresses[jx].IP, subset.Ports[0].Port))
		}
	}
	return result, nil
}

func getClientset() (ptr *kubernetes.Clientset, err error) {
	var config *rest.Config
	if *inKubeCluster {
		config, err = rest.InClusterConfig()
		if err != nil {
			return nil, err
		}
	} else {
		config, err = clientcmd.BuildConfigFromFlags("", *kubeconfig)
		if err != nil {
			return nil, err
		}
	}
	return kubernetes.NewForConfig(config)
}

func main() {
	pflag.Parse()

	pathShardRegexp := regexp.MustCompile(*pathShardExpr)

	s := &sharder.Sharder{
		PathRE: pathShardRegexp,
		Scheme: "http",
	}

	var serverAddresses []string
	if len(*addresses) > 0 {
		serverAddresses = *addresses
	} else if len(*kubernetesService) > 0 {
		clientset, err := getClientset()
		if err != nil {
			glog.Errorf("Error contacting server: %v", err)
			os.Exit(1)
		}
		serverAddresses, err = getKubernetesAddresses(clientset)
		if err != nil {
			glog.Errorf("Error contacting server: %v", err)
			os.Exit(1)
		}
		go func() {
			// TODO: use watch here
			ticker := time.Tick(time.Second * 5)
			for range ticker {
				addresses, err := getKubernetesAddresses(clientset)
				if err != nil {
					glog.Errorf("Error getting addresses: %v", err)
					continue
				}
				glog.Infof("Sharder updating, spreading load to %v", addresses)
				s.UpdateAddresses(addresses)
			}
		}()
	} else {
		fmt.Printf("Either --addresses or --kubernetes-service are required.\n")
		os.Exit(1)
	}

	glog.Infof("Sharder starting, spreading load to %v", serverAddresses)

	s.SetAddresses(serverAddresses)

	proxy := &httputil.ReverseProxy{
		Director: s.DirectorFn,
	}

	http.Handle("/", proxy)
	http.ListenAndServe(*address, nil)
}
