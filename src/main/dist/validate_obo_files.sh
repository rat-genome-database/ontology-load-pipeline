# ensure that OBO files are compliant with OBO spec
# we do this by running robot tool: we try to convert the obo files to OWL format
# note: robot.jar should be available in pipeline directory
#
. /etc/profile
APPDIR=/home/rgddata/pipelines/OntologyLoad
SERVER=`hostname -s | tr '[a-z]' '[A-Z]'`
ONT_DIR="/home/rgddata/ontology"

obo_files=()
obo_files+=("$ONT_DIR/pathway/pathway.obo")
obo_files+=("$ONT_DIR/clinical_measurement/clinical_measurement.obo")
obo_files+=("$ONT_DIR/measurement_method/measurement_method.obo")
obo_files+=("$ONT_DIR/experimental_condition/experimental_condition.obo")
obo_files+=("$ONT_DIR/disease/RDO.obo")
obo_files+=("$ONT_DIR/rat_strain/rat_strain.obo")

owl_files=("PW.owl" "CMO.owl" "MMO.owl" "XCO.owl" "RDO.owl" "RS.owl")
error_file=robot.errors

cd $APPDIR

for i in ${!obo_files[@]}; do
  infile=${obo_files[$i]}
  outfile="data/${owl_files[$i]}"
  java -jar robot.jar convert --input $infile --output $outfile >> $error_file
done

if [ -s $error_file ]; then
  mailx -s "[$SERVER] problematic obo files; run robot tool in verbose mode" mtutaj@mcw.edu < $error_file
fi
