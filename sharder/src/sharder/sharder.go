package sharder

import (
	"fmt"
	"net/http"
	"regexp"
	"sync"

	"github.com/golang/glog"
	"github.com/serialx/hashring"
)

type Sharder struct {
	PathRE *regexp.Regexp
	Scheme string

	lock      sync.RWMutex
	addresses []string
	ring      *hashring.HashRing
}

func difference(current, next []string, addFn, removeFn func(string)) {
	currentMap := map[string]bool{}
	for _, addr := range current {
		currentMap[addr] = true
	}

	nextMap := map[string]bool{}
	for _, addr := range next {
		nextMap[addr] = true
		if !currentMap[addr] {
			addFn(addr)
		}
	}

	for addr := range currentMap {
		if !nextMap[addr] {
			removeFn(addr)
		}
	}
}

func (s *Sharder) SetAddresses(addresses []string) {
	s.lock.Lock()
	defer s.lock.Unlock()
	s.addresses = addresses
	s.ring = hashring.New(s.addresses)
}

func (s *Sharder) UpdateAddresses(addresses []string) {
	s.lock.Lock()
	defer s.lock.Unlock()

	addFn := func(str string) { s.ring = s.ring.AddNode(str) }
	removeFn := func(str string) { s.ring = s.ring.RemoveNode(str) }
	difference(s.addresses, addresses, addFn, removeFn)
	s.addresses = addresses
}

func Shard(pathRE *regexp.Regexp, path string, ring *hashring.HashRing) (string, error) {
	match := pathRE.FindStringSubmatch(path)
	if match != nil && len(match) > 1 && len(match[1]) != 0 {
		path = match[1]
	}

	node, ok := ring.GetNode(path)
	if !ok {
		return "", fmt.Errorf("Failed to shard for %s", path)
	}
	return node, nil
}

func (s *Sharder) DirectorFn(req *http.Request) {
	shard, err := Shard(s.PathRE, req.URL.Path, s.ring)
	if err != nil {
		glog.Errorf("Error directing shard request: %v", err)
	}
	glog.Infof("Sending to %s\n", shard)
	req.URL.Host = shard
	req.URL.Scheme = s.Scheme
}
