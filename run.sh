#!/bin/bash

#Usage
#   ./run.sh                    - run with defaults
#   ./run.sh -p 9878 -n node2   - customer port and name

cd "$(dirname "$0")"
java -Djava.net.preferIPv4Stack=true -cp out com.sendx.Main "$@"