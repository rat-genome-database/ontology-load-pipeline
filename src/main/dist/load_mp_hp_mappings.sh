# load MP-HP mappings from the MGI SSSOM mapping file:
# every mapping gets an 'xref' synonym and a typed label synonym on the MP term,
# and a reciprocal 'xref' synonym on the HP term
#
. /etc/profile
APPDIR=/home/rgddata/pipelines/ontology-load-pipeline
SERVER=`hostname -s`

cd $APPDIR

$APPDIR/_run.sh -mp_hp_mappings -skip_downloads -skip_stats_update
