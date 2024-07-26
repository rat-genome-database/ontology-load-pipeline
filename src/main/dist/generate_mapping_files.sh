# generate mapping files in SSSOM TSV format for EFO ontology
# the files will be generated in subdirectory 'data/ontology/mappings'
#
. /etc/profile
APPDIR=/home/rgddata/pipelines/OntologyLoad
SERVER=`hostname -s`

cd $APPDIR

$APPDIR/_run.sh -sssom_generator -skip_downloads -skip_stats_update
