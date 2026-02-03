# check an ontology (given ontology id) for cycles
#
. /etc/profile
APPDIR=/home/rgddata/pipelines/OntologyLoad
cd $APPDIR

if [ "$1" == "" ]; then
  echo "Please enter a parameter for ontology id, for example RDO, or * for all public ontologies"
  echo ""
  echo "  example 1  -- run check for cycles for EFO ontology"
  echo "  ./check_for_cycles.sh EFO"
  echo "  example 2  -- run check for cycles for all public ontologies"
  echo "  ./check_for_cycles.sh '*'"
  exit -1
fi

$APPDIR/_run.sh -checkForCycles=$1
