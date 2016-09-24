#!/bin/bash

version=$(git describe --always --dirty)

cp ../bin/server ./server

echo "Building brendanburns/sharder:${version}"

docker build -t brendanburns/sharder:${version} .

rm ./server

