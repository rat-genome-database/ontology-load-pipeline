# check an ontology (given ontology id) for cycles
#
. /etc/profile
APPDIR=/home/rgddata/pipelines/OntologyLoad
cd $APPDIR

if [ "$1" == "" ]; then
  echo "Please enter a parameter for ontology id, for example RDO, or * for all public ontologies"
  exit -1
fi

$APPDIR/_run.sh -checkForCycles=$1
