# check an ontology (given ontology id) for cycles
#
. /etc/profile
APPDIR=/home/rgddata/pipelines/OntologyLoad
cd $APPDIR

if [ "%1" == "" ]; then
  echo "Please enter a parameter for ontology id, for example RDO"
  exit -1
fi

$APPDIR/_run.sh -checkForCycles -single_ontology=%1 -skip_downloads -skip_stats_update
