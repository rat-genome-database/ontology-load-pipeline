# generate obo files for ontologies that are live-edited in RGD
#
. /etc/profile
APPDIR=/home/rgddata/pipelines/ontology-load-pipeline
SERVER=`hostname -s`
ONT_RELEASE_DIR="rgdpub@${SERVER}.rgd.mcw.edu:/rgd/data/ontology"
if [ "$SERVER" != "reed" ]; then
  ONT_RELEASE_DIR="/home/rgddata/ontology"
fi

cd $APPDIR

# generate obo files for all ontologies specified in config file AppConfigure.xml
$APPDIR/_run.sh -generate_obo_file=

# copy all the files to the data release directory for ontologies
scp -pr $APPDIR/data/ontology/* $ONT_RELEASE_DIR

# email the summary: which obo files were generated and which have a new version
mailx -s "[$SERVER] OBO files generated" mtutaj@mcw.edu < $APPDIR/logs/oboFiles_summary.log
