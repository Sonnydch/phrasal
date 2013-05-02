#!/usr/bin/env bash
#
# Apply de-tokenization followed by re-casing.
#
# Author: Spence Green
#

if [ $# -ne 1 ]; then
    echo Usage: $(basename $0) file
    exit -1
fi

infile=$1

# De-compounding
cat $infile | de-rules.py > $infile.decompound

# Truecasing
recase.sh German $infile.decompound > $infile.cased

# POS tag section
## get POS tags here... [command] > $infile.pos

de-morph.py $infile.cased $infile.pos > $infile.morph

# Detokenization
cat $infile.morph | en_detokenizer | de-post.py > $infile.postproc

rm -f $infile.{cased,decompound,pos,morph}
