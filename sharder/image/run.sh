#!/bin/bash

if [ ! -z "${SERVER_ADDRESS}" ]; then
    ADDR="--address=${SERVER_ADDRESS}"
fi

if [ -z "${SHARD_ADDRESSES}" ]; then
    echo "SHARD_ADDRESSES is required"
    exit 1
fi

if [ ! -z "${PATH_REGEXP}" ]; then
    PATH_RE="--path-shard-expression=${PATH_REGEXP}"
fi

/server --addresses=${SHARD_ADDRESSES} ${ADDR} ${PATH_RE}