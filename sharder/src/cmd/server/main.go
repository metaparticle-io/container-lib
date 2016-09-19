package main

import (
	"net/http"
	"net/http/httputil"
	"regexp"
	"sharder"

	"github.com/spf13/pflag"
)

var (
	addresses     = pflag.StringSlice("addresses", []string{}, "The list of addresses to shard to")
	pathShardExpr = pflag.String("path-shard-expression", "", "The path sharding expression, the first group will be used for sharding, if empty, the entire path is used.")
	address       = pflag.String("address", "localhost:8080", "The <host>:<port> to serve on")
)

func main() {
	pflag.Parse()

	pathShardRegexp := regexp.MustCompile(*pathShardExpr)

	s := &sharder.Sharder{
		PathRE: pathShardRegexp,
	}

	proxy := &httputil.ReverseProxy{
		Director: s.DirectorFn,
	}

	http.Handle("/", proxy)
	http.ListenAndServe(*address, nil)
}
