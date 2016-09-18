package main

import (
	"hash/fnv"
	"net/http"
	"net/http/httputil"
	"regexp"

	"github.com/golang/glog"
	"github.com/spf13/pflag"
)

var (
	addresses       = pflag.StringSlice("addresses", []string{}, "The list of addresses to shard to")
	pathShardExpr   = pflag.String("path-shard-expression", "", "The path sharding expression, the first group will be used for sharding, if empty, the entire path is used.")
	address         = pflag.String("address", "localhost:8080", "The <host>:<port> to serve on")
	pathShardRegexp *regexp.Regexp
)

func shard(pathRE *regexp.Regexp, path string, shardCount uint32) (int, error) {
	match := pathRE.FindStringSubmatch(path)
	if match != nil && len(match) > 1 && len(match[1]) != 0 {
		path = match[1]
	}

	hasher := fnv.New32()
	if _, err := hasher.Write([]byte(path)); err != nil {
		return -1, err
	}
	return int(hasher.Sum32() % shardCount), nil
}

func directorFn(req *http.Request) {
	shardNum, err := shard(pathShardRegexp, req.URL.Path, uint32(len(*addresses)))
	if err != nil {
		glog.Errorf("Error directing shard request: %v", err)
	}
	glog.Infof("Sending to %s\n", (*addresses)[shardNum])
	req.URL.Host = (*addresses)[shardNum]
	req.URL.Scheme = "http"
}

func main() {
	pflag.Parse()

	pathShardRegexp = regexp.MustCompile(*pathShardExpr)

	proxy := &httputil.ReverseProxy{
		Director: directorFn,
	}

	http.Handle("/", proxy)
	http.ListenAndServe(*address, nil)
}
