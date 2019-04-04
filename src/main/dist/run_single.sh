# loads single ontology, given ontology id as parameter
# for example:  ./run_single.sh CL
# other parameters could follow the ontology id, for example:
#   ./run_single.sh RS -drop_synonyms
# will first drop existing synonyms for RS ontology, and then load it from source file

. /etc/profile
APPDIR=/home/rgddata/pipelines/OntologyLoad

# loading GO ontology will automatically load and process GO taxon constraints
if [ "$1" == "GO" ]; then
  GO_TAXON_CONSTRAINTS="-go_taxon_constraints"
else
  GO_TAXON_CONSTRAINTS=""
fi

cd $APPDIR
$APPDIR/_run.sh $GO_TAXON_CONSTRAINTS \
  -single_ontology=$1 $2 $3 $4 $5 $6 $7 $8 $9
