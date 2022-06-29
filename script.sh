#!/bin/bash

# libgeneral
git clone 'https://github.com/tihmstar/libgeneral.git'
cd libgeneral/
./autogen.sh
make
make install
cd ../
# libfragmentzip
git clone 'https://github.com/tihmstar/libfragmentzip.git'
cd libfragmentzip/
./autogen.sh 
make
make install
cd ../
# partialzipbrowser
git clone 'https://github.com/tihmstar/partialZipBrowser.git'
cd partialZipBrowser/
./autogen.sh
make
make install
cd ../

echo 'Done!'
