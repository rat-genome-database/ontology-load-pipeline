# generate obo file for given ontology
# for example:  ./generate_obo_file.sh RDO
#
. /etc/profile
APPDIR=/home/rgddata/pipelines/OntologyLoad
SERVER=`hostname -s`

cd $APPDIR

$APPDIR/_run.sh -generate_obo_file=$1 -skip_downloads -skip_stats_update

if [ "$1" == "RDO" ]; then
  #copy RDO.obo file to RDO_yyyymmdd.obo file and copy this file to data release dir for ontologies
  scp $APPDIR/data/$1.obo $APPDIR/data/$1_`date +%Y%m%d`.obo
fi
