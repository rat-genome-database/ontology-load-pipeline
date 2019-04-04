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

# PATHWAY
$APPDIR/_run.sh -generate_obo_file=PW -process_obsolete_terms -prod_release

scp -p $APPDIR/data/ontology/pathway/*.obo $ONT_RELEASE_DIR/pathway
echo "staging dir: $ONT_RELEASE_DIR/pathway"
echo

# CLINICAL MEASUREMENT
$APPDIR/_run.sh -generate_obo_file=CMO -process_obsolete_terms -prod_release

scp -p $APPDIR/data/ontology/clinical_measurement/*.obo $ONT_RELEASE_DIR/clinical_measurement
echo "staging dir: $ONT_RELEASE_DIR/clinical_measurement"
echo

# EXPERIMENTAL CONDITION
$APPDIR/_run.sh -generate_obo_file=XCO -process_obsolete_terms -prod_release

scp -p $APPDIR/data/ontology/experimental_condition/*.obo $ONT_RELEASE_DIR/experimental_condition
echo "staging dir: $ONT_RELEASE_DIR/experimental_condition"
echo

# MEASUREMENT METHOD
$APPDIR/_run.sh -generate_obo_file=MMO -process_obsolete_terms -prod_release

scp -p $APPDIR/data/ontology/measurement_method/*.obo $ONT_RELEASE_DIR/measurement_method
echo "staging dir: $ONT_RELEASE_DIR/measurement_method"
echo


# RDO
$APPDIR/_run.sh -generate_obo_file=RDO -prod_release

scp -p $APPDIR/data/ontology/disease/*.obo $ONT_RELEASE_DIR/disease
echo "staging dir: $ONT_RELEASE_DIR/disease"
echo


# RS
$APPDIR/_run.sh -generate_obo_file=RS -prod_release

scp -p $APPDIR/data/ontology/rat_strain/*.obo $ONT_RELEASE_DIR/rat_strain
echo "staging dir: $ONT_RELEASE_DIR/rat_strain"
echo
