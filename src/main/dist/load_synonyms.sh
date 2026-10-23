# load RDO term synonyms from a tab-separated file
#   columns: synonym, synonym type (broad/exact/narrow/related), term name, term acc
#
. /etc/profile
APPDIR=/home/rgddata/pipelines/ontology-load-pipeline

if [ "$1" == "" ]; then
  echo "Please enter the name of the file with synonyms, for example:"
  echo "  ./load_synonyms.sh data/synonyms.txt"
  exit -1
fi

cd $APPDIR

$APPDIR/_run.sh "-load_synonyms=$1" -skip_downloads -skip_stats_update
