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
	// TODO: Probably want a full service spec here
	Port int `json:"port" description: "The port that this serves on"`
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
		glog.V(5).Infof("Received shardeds:\n%#v\n", list)

		for ix := range list.Items {
			item := &list.Items[ix]
			if err := reconcileItem(clientset, item); err != nil {
				glog.Errorf("Failed to reconcile: %v", err)
				continue
			}
		}
	}
}

func labelsEqual(a, b map[string]string) bool {
	if len(a) != len(b) {
		return false
	}
	for k, v := range a {
		v2, found := b[k]
		if !found {
			return false
		}
		if v != v2 {
			return false
		}
	}
	return true
}

func reconcileDeployment(clientset *kubernetes.Clientset, namespace, deploymentName string, count int, container *v1.Container, labels map[string]string) error {
	d, err := clientset.Extensions().Deployments(namespace).Get(deploymentName)
	if err != nil && !errors.IsNotFound(err) {
		return err
	}
	if errors.IsNotFound(err) {
		d = &v1beta1.Deployment{
			ObjectMeta: v1.ObjectMeta{
				Name: deploymentName,
			},
			Spec: v1beta1.DeploymentSpec{
				Replicas: util.Int32Ptr(int32(count)),
				Selector: &v1beta1.LabelSelector{
					MatchLabels: labels,
				},
				Template: v1.PodTemplateSpec{
					ObjectMeta: v1.ObjectMeta{
						Labels: labels,
					},
					Spec: v1.PodSpec{
						Containers: []v1.Container{
							*container,
						},
					},
				},
			},
		}
		if _, err := clientset.Extensions().Deployments(namespace).Create(d); err != nil {
			return err
		}
	} else {
		if *d.Spec.Replicas != int32(count) {
			d.Spec.Replicas = util.Int32Ptr(int32(count))
			if _, err := clientset.Extensions().Deployments(namespace).Update(d); err != nil {
				return err
			}
		}
	}
	return nil
}

func reconcileService(clientset *kubernetes.Clientset, serviceNamespace, serviceName string, port int32, labels map[string]string) error {
	s, err := clientset.Core().Services(serviceNamespace).Get(serviceName)
	if err != nil && !errors.IsNotFound(err) {
		return err
	}
	if err == nil {
		if labelsEqual(labels, s.Spec.Selector) {
			return nil
		}
		s.Spec.Selector = labels
		_, err := clientset.Core().Services(serviceNamespace).Update(s)
		return err
	}
	s = &v1.Service{
		ObjectMeta: v1.ObjectMeta{
			Name:      serviceName,
			Namespace: serviceNamespace,
		},
		Spec: v1.ServiceSpec{
			Selector: labels,
			Ports: []v1.ServicePort{
				v1.ServicePort{
					Port: port,
				},
			},
		},
	}
	return nil
}

func reconcileItem(clientset *kubernetes.Clientset, sharded *Sharded) error {
	glog.V(5).Infof("Reconciling: %v\n", sharded)
	// TODO: Add randomness here
	deploymentName := sharded.Name + "-sharder"
	labels := map[string]string{
		"app": deploymentName,
	}

	container := v1.Container{
		Name: "shard-server",
		// TODO: make this a flag
		Image: "brendanburns/sharder:92d6df4",
		Command: []string{
			"/server",
			"--kubernetes-service=" + sharded.Spec.DelegateServiceName,
		},
	}

	if err := reconcileDeployment(clientset, sharded.Namespace, deploymentName, sharded.Spec.ShardServerCount, &container, labels); err != nil {
		return err
	}
	if err := reconcileService(clientset, sharded.Namespace, sharded.Spec.ShardedServiceName, int32(sharded.Spec.Port), labels); err != nil {
		return err
	}

	deploymentName = sharded.Name + "-sharded"
	labels["app"] = deploymentName
	container = v1.Container{
		Name:  "delegate-server",
		Image: sharded.Spec.DelegateImage,
	}

	if err := reconcileDeployment(clientset, sharded.Namespace, deploymentName, sharded.Spec.ShardServerCount, &container, labels); err != nil {
		return err
	}
	if err := reconcileService(clientset, sharded.Namespace, sharded.Spec.DelegateServiceName, int32(sharded.Spec.Port), labels); err != nil {
		return err
	}

	return nil
}
