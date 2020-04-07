# generate obo files for ontologies that are live-edited in RGD
#
. /etc/profile
APPDIR=/home/rgddata/pipelines/OntologyLoad
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
