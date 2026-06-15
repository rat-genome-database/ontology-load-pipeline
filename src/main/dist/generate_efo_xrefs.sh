# generate reciprocate EFO xrefs for CMO, VT, MP, HP and RDO ontologies
#
. /etc/profile
APPDIR=/home/rgddata/pipelines/ontology-load-pipeline
SERVER=`hostname -s`

cd $APPDIR

$APPDIR/_run.sh -efo_xrefs -skip_downloads -skip_stats_update
