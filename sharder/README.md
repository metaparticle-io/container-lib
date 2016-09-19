# Sharder

## Overview
Sharder is an image that knows how to shard HTTP requests out to a list of
servers, optionally sharding only on things that match a certain regular
expression.

## Usage
Sharder has three parameters that can be supplied as environment variables:
   * `SHARD_ADDRESSES` - Required - A comma separated list of shards to pass to
   * `PATH_REGEXP` - Optional - A reglar expression containing a group, anything matching the group is used hashed to get the shard, anything not in that group is ignored.  If the regexp doesn't match, the entire original path is used.
   * `SERVER_ADDRESS` - Optional - The <host>:<port> to serve on, default is
`localhost:8080`

## Examples

```
docker run -e SHARD_ADDRESSES=http://localhost:8001,http://localhost:8002 brendanburns/sharder:<hash>
```

Shard to two different sites, hashing the entire HTTP Path

```
docker run -e SHARD_ADDRESSES=http://localhost:8001,http://localhost:8002 -e "PATH_REGEXP=.*/(foo/[^/]*)/.*"brendanburns/sharder:<hash>
```

Shard to two different sites, hashing on `foo/.*` extracted from the path.

