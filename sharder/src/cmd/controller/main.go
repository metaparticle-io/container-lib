package main

import (
	"encoding/json"
	"os"
	"path"
	"time"

	"github.com/golang/glog"
	"github.com/spf13/pflag"
	"k8s.io/client-go/1.4/kubernetes"
	"k8s.io/client-go/1.4/pkg/api"
	"k8s.io/client-go/1.4/pkg/api/errors"
	"k8s.io/client-go/1.4/pkg/api/unversioned"
	"k8s.io/client-go/1.4/pkg/api/v1"
	"k8s.io/client-go/1.4/pkg/apis/extensions/v1beta1"
	"k8s.io/client-go/1.4/pkg/util"
	"k8s.io/client-go/1.4/rest"
	"k8s.io/client-go/1.4/tools/clientcmd"
)

var (
	kubeconfig    = pflag.String("kubeconfig", path.Join(os.Getenv("HOME"), ".kube/config"), "absolute path to the kubeconfig file")
	inKubeCluster = pflag.Bool("in-cluster-kubernetes-config", true, "If true, the default, use the in cluster kubernetes configuration.")
	pollDuration  = pflag.Duration("poll-duration", time.Second*30, "The length of time between polls")
)

// TODO: this is duplicated, refactor to a common location
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

type Sharded struct {
	unversioned.TypeMeta `json:",inline"`
	api.ObjectMeta       `json:"metadata,omitempty" description:"standard object metadata"`

	Spec   ShardedSpec   `json: "spec,omitempty"`
	Status ShardedStatus `json: "status,omitempty"`
}

type ShardedSpec struct {
	ShardedServiceName  string `json:"shardedServiceName" description:"The name of the user-facing service"`
	DelegateServiceName string `json:"delegateServiceName" description:"The name of the delegate service that is called by the sharder"`
	ShardCount          int    `json:"shardCount" description: "The number of shards"`
	ShardServerCount    int    `json:"shardServerCount" description: "The number of shard servers"`
	// TODO: Probably want a full pod spec here
	DelegateImage string `json:"delegateImage" description: "The delegate image to run"`
}

type ShardedStatus struct {
}

type ShardedList struct {
	unversioned.TypeMeta `json:",inline"`
	unversioned.ListMeta `json:"metadata,omitempty"`

	Items []Sharded `json:"items"`
}

func main() {
	pflag.Parse()
	// uses the current context in kubeconfig
	config, err := clientcmd.BuildConfigFromFlags("", *kubeconfig)
	if err != nil {
		panic(err.Error())
	}
	// creates the clientset
	clientset, err := kubernetes.NewForConfig(config)
	if err != nil {
		panic(err.Error())
	}

	rsrc, err := clientset.Extensions().ThirdPartyResources().Get("sharded.metaparticle.io")
	if err != nil && !errors.IsNotFound(err) {
		panic(err.Error())
	}
	if errors.IsNotFound(err) {
		rsrc = &v1beta1.ThirdPartyResource{
			ObjectMeta: v1.ObjectMeta{
				Name: "sharded.metaparticle.io",
			},
			Description: "A sharded service",
			Versions: []v1beta1.APIVersion{
				v1beta1.APIVersion{
					Name: "v1",
				},
			},
		}
		rsrc, err = clientset.Extensions().ThirdPartyResources().Create(rsrc)
	}

	tick := time.Tick(*pollDuration)
	// TODO: use watch?
	for range tick {
		data, err := clientset.Core().GetRESTClient().Get().
			AbsPath("apis/metaparticle.io/v1/namespaces/default/shardeds").
			DoRaw()

		if err != nil {
			glog.Errorf("Error listing Sharded objects: %v", err)
			continue
		}

		list := ShardedList{}
		if err := json.Unmarshal(data, &list); err != nil {
			glog.Errorf("Error decoding list: %v", err)
			continue
		}
		glog.Infof("FOO: %#v\n", list)

		for ix := range list.Items {
			item := &list.Items[ix]
			reconcileItem(clientset, item)
		}
	}
}

func reconcileItem(clientset *kubernetes.Clientset, sharded *Sharded) {
	// TODO: Add randomness here
	deploymentName := sharded.Name

	d, err := clientset.Extensions().Deployments(sharded.Namespace).Get(deploymentName)
	if err != nil && !errors.IsNotFound(err) {
		glog.Errorf("Error reconciling sharded: %v (%v)", sharded, err)
		return
	}
	labels := map[string]string{
		"app": deploymentName,
	}
	glog.Infof("Reconciling: %v\n", sharded)
	if errors.IsNotFound(err) {
		d = &v1beta1.Deployment{
			ObjectMeta: v1.ObjectMeta{
				Name: deploymentName,
			},
			Spec: v1beta1.DeploymentSpec{
				Replicas: util.Int32Ptr(int32(sharded.Spec.ShardServerCount)),
				Selector: &v1beta1.LabelSelector{
					MatchLabels: labels,
				},
				Template: v1.PodTemplateSpec{
					ObjectMeta: v1.ObjectMeta{
						Labels: labels,
					},
					Spec: v1.PodSpec{
						Containers: []v1.Container{
							v1.Container{
								Name: "shard-server",
								// TODO: make this a flag
								Image: "brendanburns/sharder:92d6df4",
								Command: []string{
									"/server",
									"--kubernetes-service=" + sharded.Spec.DelegateServiceName,
								},
							},
						},
					},
				},
			},
		}
		_, err := clientset.Extensions().Deployments(sharded.Namespace).Create(d)
		if err != nil {
			glog.Errorf("Error creating deployment: %v", err)
			return
		}
	} else {
		if *d.Spec.Replicas != int32(sharded.Spec.ShardServerCount) {
			d.Spec.Replicas = util.Int32Ptr(int32(sharded.Spec.ShardServerCount))
			_, err := clientset.Extensions().Deployments(sharded.Namespace).Update(d)
			if err != nil {
				glog.Errorf("Error creating deployment: %v", err)
				return
			}
		}
	}
}
