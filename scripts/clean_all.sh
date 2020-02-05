#!/bin/bash
THIS_FILE=`dirname $0`

rm -rfv $THIS_FILE/../temp/*
rm -rfv $THIS_FILE/../html
rm -rfv $THIS_FILE/../src/main/resources/repository/*
rm -rfv $THIS_FILE/../target/journal
find $THIS_FILE/../src/main/resources/targets/ -type f -not -name 'target_test.csv' -print0 | xargs -0  -I {} rm -rfv {}
