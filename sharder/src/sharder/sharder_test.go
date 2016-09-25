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

func TestDifference(t *testing.T) {
	tests := []struct {
		existing []string
		next     []string
		add      []string
		removed  []string
		name     string
	}{
		{
			[]string{"a", "b"},
			[]string{},
			[]string{},
			[]string{"a", "b"},
			"delete all",
		},
		{
			[]string{},
			[]string{"a", "b"},
			[]string{"a", "b"},
			[]string{},
			"add all",
		},
		{
			[]string{"a", "b"},
			[]string{"a", "b"},
			[]string{},
			[]string{},
			"do nothing",
		},
		{
			[]string{"a", "b"},
			[]string{"a", "b", "c"},
			[]string{"c"},
			[]string{},
			"add one",
		},
		{
			[]string{"a", "b", "c"},
			[]string{"a", "b"},
			[]string{},
			[]string{"c"},
			"remove one",
		},
		{
			[]string{"a", "b", "c"},
			[]string{"a", "b", "d"},
			[]string{"d"},
			[]string{"c"},
			"both",
		},
	}

	for _, test := range tests {
		added := map[string]bool{}
		deleted := map[string]bool{}
		difference(test.existing, test.next,
			func(str string) {
				added[str] = true
			},
			func(str string) {
				deleted[str] = true
			})
		if len(added) != len(test.add) {
			t.Errorf("unexpected lengths %d vs %d in %s", len(added), len(test.add), test.name)
		}
		for _, str := range test.add {
			if !added[str] {
				t.Errorf("failed to find %s in %s", str, test.name)
			}
		}

		if len(deleted) != len(test.removed) {
			t.Errorf("unexpected lengths %d vs %d in %s", len(deleted), len(test.removed), test.name)
		}
		for _, str := range test.removed {
			if !deleted[str] {
				t.Errorf("failed to find %s in %s", str, test.name)
			}
		}
	}
}
