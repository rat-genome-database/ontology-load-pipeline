# ensure that OBO files are compliant with OBO spec
# we do this by running robot tool: we try to convert the obo files to OWL format
# note: robot.jar should be available in pipeline directory
#
. /etc/profile
APPDIR=/home/rgddata/pipelines/OntologyLoad
SERVER=`hostname -s`
if [ "$SERVER" == "reed" ]; then
  ONT_DIR="rgdpub@${SERVER}.rgd.mcw.edu:/rgd/data/ontology"
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

obo_tmp_files=()
obo_tmp_files+=("/tmp/PW.obo")
obo_tmp_files+=("/tmp/CMO.obo")
obo_tmp_files+=("/tmp/MMO.obo")
obo_tmp_files+=("/tmp/XCO.obo")
obo_tmp_files+=("/tmp/RDO.obo")
obo_tmp_files+=("/tmp/RS.obo")

#generated owl files
owl_files=()
owl_files+=("$ONT_DIR/pathway/pathway.owl")
owl_files+=("$ONT_DIR/clinical_measurement/clinical_measurement.owl")
owl_files+=("$ONT_DIR/measurement_method/measurement_method.owl")
owl_files+=("$ONT_DIR/experimental_condition/experimental_condition.owl")
owl_files+=("$ONT_DIR/disease/RDO.owl")
owl_files+=("$ONT_DIR/rat_strain/rat_strain.owl")

owl_tmp_files=()
owl_tmp_files+=("/tmp/PW.owl")
owl_tmp_files+=("/tmp/CMO.owl")
owl_tmp_files+=("/tmp/MMO.owl")
owl_tmp_files+=("/tmp/XCO.owl")
owl_tmp_files+=("/tmp/RDO.owl")
owl_tmp_files+=("/tmp/RS.owl")


error_file="$APPDIR/robot.errors"
echo " " > $error_file

cd $APPDIR

for i in ${!obo_files[@]}; do
  infile=${obo_tmp_files[$i]}
  outfile="${owl_tmp_files[$i]}"

  scp -p "${obo_files[$i]}" $infile
  java -jar robot.jar convert --input $infile --output $outfile >> $error_file
  scp -p $outfile "${owl_files[$i]}"
done

if [ -s $error_file ]; then
  mailx -s "[$SERVER] problematic obo files; run robot tool in verbose mode" mtutaj@mcw.edu < $error_file
fi
