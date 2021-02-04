# ensure that OBO files are compliant with OBO spec
# we do this by running robot tool: we try to convert the obo files to OWL format
# note: robot.jar should be available in pipeline directory
#
. /etc/profile
APPDIR=/home/rgddata/pipelines/OntologyLoad
SERVER=`hostname -s | tr '[a-z]' '[A-Z]'`
if [ "$SERVER" == "REED" ]; then
  ONT_DIR="/data/data/ontology"
else # TRAVIS
  ONT_DIR="/home/rgddata/ontology"
fi

# source obo files
obo_files=()
obo_files+=("$ONT_DIR/pathway/pathway.obo")
obo_files+=("$ONT_DIR/clinical_measurement/clinical_measurement.obo")
obo_files+=("$ONT_DIR/measurement_method/measurement_method.obo")
obo_files+=("$ONT_DIR/experimental_condition/experimental_condition.obo")
obo_files+=("$ONT_DIR/disease/RDO.obo")
obo_files+=("$ONT_DIR/rat_strain/rat_strain.obo")

#generated owl files
owl_files=()
owl_files+=("$ONT_DIR/pathway/pathway.owl")
owl_files+=("$ONT_DIR/clinical_measurement/clinical_measurement.owl")
owl_files+=("$ONT_DIR/measurement_method/measurement_method.owl")
owl_files+=("$ONT_DIR/experimental_condition/experimental_condition.owl")
owl_files+=("$ONT_DIR/disease/RDO.owl")
owl_files+=("$ONT_DIR/rat_strain/rat_strain.owl")

error_file=robot.errors
echo " " > $error_file

cd $APPDIR

for i in ${!obo_files[@]}; do
  infile=${obo_files[$i]}
  outfile="${owl_files[$i]}"
  java -jar robot.jar convert --input $infile --output $outfile >> $error_file
done

if [ -s $error_file ]; then
  mailx -s "[$SERVER] problematic obo files; run robot tool in verbose mode" mtutaj@mcw.edu < $error_file
fi
