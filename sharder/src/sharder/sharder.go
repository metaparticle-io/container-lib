package sharder

import (
	"hash/fnv"
	"net/http"
	"regexp"

	"github.com/golang/glog"
)

type Sharder struct {
	PathRE    *regexp.Regexp
	Addresses []string
	Scheme    string
}

func Shard(pathRE *regexp.Regexp, path string, shardCount uint32) (int, error) {
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

func (s *Sharder) DirectorFn(req *http.Request) {
	shardNum, err := Shard(s.PathRE, req.URL.Path, uint32(len(s.Addresses)))
	if err != nil {
		glog.Errorf("Error directing shard request: %v", err)
	}
	glog.Infof("Sending to %s\n", s.Addresses[shardNum])
	req.URL.Host = s.Addresses[shardNum]
	req.URL.Scheme = s.Scheme
}
