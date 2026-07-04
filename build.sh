#!/bin/bash
set -e

echo "Compiling SendX..."
rm -rf out
mkdir -p out

find src -name "*.java" | xargs javac -d out

echo "Build successful. Run with: ./run.sh"