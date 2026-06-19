package edu.mcw.rgd.dataload.ontologies.test;

import edu.mcw.rgd.process.Utils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SortTerm {

    public static void main(String[] args) throws IOException {

        String fname1 = "h:/do/new.obo";
        String fname2 = "h:/do/new2.obo";

        BufferedReader in = Utils.openReader(fname1);
        BufferedWriter out = Utils.openWriter(fname2);

        List<String> section = new ArrayList<>();
        String line;
        while( (line=in.readLine())!=null ) {
            if( Utils.isStringEmpty(line) ) {
                // new section detected
                Collections.sort(section);

                for( String s: section ) {
                    out.write(s);
                    out.write("\n");
                }
                out.write("\n");
                section.clear();
            } else {
                section.add(line);
            }
        }

        // final section
        Collections.sort(section);

        for( String s: section ) {
            out.write(s);
            out.write("\n");
        }

        in.close();
        out.close();
    }
}
