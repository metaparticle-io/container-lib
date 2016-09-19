#!/bin/bash

if [ ! -z "$(git status --porcelain)" ]; then
    dirty="-dirty"
fi

version=$(git rev-parse HEAD)

cp ../bin/server ./server

echo "Building ${version}${dirty}"

docker build -t brendanburns/sharder:${version}${dirty} .

rm ./server
