# launcher-catalog-converter
Quick &amp; dirty app to convert from the old-style booster catalog format to the new one

## Build

   $ mvn clean package
   
## Run

   $ java -jar target/catalog-converter-0.0.1-SNAPSHOT-jar-with-dependencies.jar
   
The above command will give you the "help" (as far as you can call it that).

For converting the RHOAR catalog you do:

   $ java -jar target/catalog-converter-0.0.1-SNAPSHOT-jar-with-dependencies.jar converted-boosters "" master next v16
   
Where "v16" is the latest tag that is in production.

