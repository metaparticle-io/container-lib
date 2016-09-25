package sharder

import (
	"fmt"
	"regexp"
	"testing"

	"github.com/serialx/hashring"
)

func TestShardRE(t *testing.T) {
	ring := hashring.New([]string{"foo", "bar", "baz"})
	re := regexp.MustCompile(".*(/foo/[^/]*)/.*")
	shard1, err := Shard(re, "/some/path/foo/bar/baz", ring)
	if err != nil {
		t.Errorf("unexpected error: %v", err)
	}
	shard2, err := Shard(re, "/some/other/path/foo/bar/baz", ring)
	if err != nil {
		t.Errorf("unexpected error: %v", err)
	}
	shard3, err := Shard(re, "/some/path/foo/bar/blah", ring)
	if err != nil {
		t.Errorf("unexpected error: %v", err)
	}

	if shard1 != shard2 {
		t.Errorf("shard1 != shard2: expected %d == %d", shard1, shard2)
	}
	if shard1 != shard3 {
		t.Errorf("shard2 != shard3: expected %d == %d", shard1, shard3)
	}
}

func TestShardDistribution(t *testing.T) {
	re := regexp.MustCompile(".*(/foo/[^/]*)/.*")
	ring := hashring.New([]string{"foo", "bar", "baz"})

	counts := map[string]int{}

	for i := 0; i < 1000; i++ {
		path := fmt.Sprintf("/foo/%d/bar/baz", i)
		shard, err := Shard(re, path, ring)
		if err != nil {
			t.Errorf("unexpected error: %v", err)
		}
		counts[shard] = counts[shard] + 1
	}

	for ix, count := range counts {
		if count < 200 {
			t.Errorf("unexpectedly low count for: %s", ix)
		}
		if count > 400 {
			t.Errorf("unexpectedly high count for: %s", ix)
		}
	}
}
