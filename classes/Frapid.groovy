import java.nio.file.Paths 
import java.nio.file.Files
import groovy.xml.MarkupBuilder
import java.security.*
import static java.nio.file.StandardCopyOption.*

class Frapid {

   def dirs, config
   
   def Frapid() {

      dirs = ["components", "lib", "config", "tmp", "media", "bin"];

      def frapidPath = System.getenv()["FRAPID_HOME"]
      config = new ConfigSlurper().parse(new File("${frapidPath}/config.groovy").toURL())
   }

   def generateKeys( path = config.frapid.home ) {
	
      def keyGen = KeyPairGenerator.getInstance("DSA", "SUN")
      keyGen.initialize(1024, SecureRandom.getInstance("SHA1PRNG", "SUN") );
      
      def keyPair = keyGen.generateKeyPair();
      def privateKey = keyPair.getPrivate();
      def publicKey = keyPair.getPublic();

      def pubKeyPath = Paths.get( path + File.separator + "public.key")
      Files.write( pubKeyPath, publicKey.encoded  )

      def privKeyPath = Paths.get( path + File.separator + "private.key")
      Files.write( privKeyPath, privateKey.encoded  )

   }


   def createProject( name, path = "." ) {

      def projectRoot = path + File.separator + name
      def toCreate = [ projectRoot ]

      toCreate += dirs.collect { projectRoot + File.separator + it } 
      toCreate.collect { Files.createDirectory( Paths.get( it ) ) }

      // create routes.xml
      def routesXml = Paths.get( config.frapid.templates + File.separator + "routes.xml" )
      def configDir = Paths.get( projectRoot + File.separator + "config/routes.xml" )
      Files.copy( routesXml, configDir )

      // create deploy.xml
      def deployXml = Paths.get( config.frapid.templates + File.separator + "deploy.xml" )
      def deployDir = Paths.get( projectRoot + File.separator + "config/deploy.xml" )
      def file = deployXml.toFile()
      file.write( file.text.replaceAll("_name_", name) )
      Files.copy( deployXml, deployDir )
     
      // create .frapid
      def frapidCheckFile = Paths.get( config.frapid.templates + File.separator + ".frapid" )
      def projectRootDir = Paths.get( projectRoot + File.separator + ".frapid" )
      Files.copy( frapidCheckFile, projectRootDir )

      // copy img
      def img = Paths.get( config.frapid.templates + File.separator + "default.jpg" )
      def mediaDir = Paths.get( projectRoot + File.separator + "media/default.jpg" )
      Files.copy( img, mediaDir )
    

      generate( "business_component", "SampleComponent", projectRoot )
      
      if( Files.notExists( Paths.get( config.frapid.home + File.separator + "public.key" )) ||
          Files.notExists( Paths.get( config.frapid.home + File.separator + "private.key")) 
      ) {

         generateKeys() 

      }

    
   }

   def generate( type, name, path = "." ) {

      path = getProjectRoot path

      if( type == "business_component" ) {

         def template = Paths.get( config.frapid.templates + File.separator + camelize(type) + ".php" )
         def destinationFolder = Paths.get( config.frapid.temp + File.separator + "${name}.php" )
    	 def component = Files.copy( template, destinationFolder, REPLACE_EXISTING )
         destinationFolder = path.resolve( "components/${name}.php" )
         def componentFile = component.toFile()

         componentFile.write( componentFile.text.replaceAll("_${type}_", name) )
    	 Files.move( component, destinationFolder, REPLACE_EXISTING )


      } else if( type == "front_controller" ) {

         def template = Paths.get( config.frapid.templates + File.separator + camelize(type) + ".php" )
         def destinationFolder = Paths.get( config.frapid.temp + File.separator + "${name}.php" )
    	 def action = Files.copy( template, destinationFolder, REPLACE_EXISTING )
         destinationFolder = Paths.get( config.frapi.action+ File.separator + "${name}.php" )
         def actionFile = action.toFile()

         actionFile.write( actionFile.text.replaceAll("_${type}_", name) )
    	 Files.move( action, destinationFolder, REPLACE_EXISTING )

      }

   }

   def deploy( projectPath ) {

     undeploy( projectPath )
     
     def projectRoot = getProjectRoot projectPath
     def app = new XmlSlurper().parse( projectRoot.resolve( "config/deploy.xml").toString() )

     def deployDir = Paths.get( config.frapi.frapid + File.separator + app.name )
     Files.createDirectory( deployDir ) 
     println "deploy dir: " + deployDir.toString()

     def path = projectRoot.resolve( "config/routes.xml" ) 
     def destinationFolder = deployDir.resolve( "routes.xml" )
     Files.copy( path, destinationFolder, REPLACE_EXISTING )

     def componentsDir = projectRoot.resolve("components").toFile()
     componentsDir.eachFileMatch( ~/.*\.php/ ) { component ->

        path = Paths.get( component.absolutePath )
        destinationFolder = deployDir.resolve( component.name ) 
	    Files.copy( path, destinationFolder, REPLACE_EXISTING )

     }


   }

   def undeploy( projectPath ) {

      def projectRoot = getProjectRoot projectPath
      def app = new XmlSlurper().parse( projectRoot.resolve( "config/deploy.xml").toString() )
      def deployDir = Paths.get( config.frapi.frapid + File.separator + app.name ).toFile()
      
      if( deployDir.exists()  ) {
         deployDir.eachFile { component ->
             component.delete()
         } 

         deployDir.delete()
      }

   }

   def pack( path = "." ) {

      path = getProjectRoot path

      def app = new XmlSlurper().parse( path.resolve( "config/deploy.xml").toString() )
      def ant = new AntBuilder()

      def archivePath = path.resolve( "bin/${app.name}.tar.gz" ) 
      if ( Files.exists( archivePath) ) Files.delete archivePath 
      ant.tar( basedir: path , destFile: archivePath, compression: "gzip" )

   }

   def unpack( file, path = "." ){

      def app = new XmlSlurper().parse("../config/deploy.xml")
      def ant = new AntBuilder()
      ant.untar( src: file, dest: "${path}/${app.name}", overwrite: true, compression: "gzip" )
 
   }

   def publish(){}


   def config() {

     // sostituisci main action
     def mainAction = Paths.get( config.frapid.templates + File.separator + "Main.php" )
     def destinationFolder = Paths.get( config.frapi.main_controller + File.separator + "Main.php" )
     Files.copy( mainAction, destinationFolder, REPLACE_EXISTING )

     // moving front controller 
     def frontController = Paths.get( config.frapid.templates + File.separator + "Frontcontroller.php"  )
     destinationFolder = Paths.get( config.frapi.action + File.separator + "Frontcontroller.php" )
     Files.copy( frontController, destinationFolder, REPLACE_EXISTING )

     // actions.xml
     def actionsXML = Paths.get( config.frapid.templates + File.separator + "actions.xml"  )
     destinationFolder = Paths.get( config.frapi.config + File.separator + "actions.xml" )
     Files.copy( actionsXML, destinationFolder, REPLACE_EXISTING )

     new File( config.frapi.custom + File.separator + "AllFiles.php" ) << '''
$it = new RecursiveDirectoryIterator( CUSTOM_PATH . DIRECTORY_SEPARATOR . 'frapid' ); 

foreach (new RecursiveIteratorIterator( $it ) as $fileInfo) {
  if($fileInfo->getExtension() == "php") {
     require_once $fileInfo->getPathname();
  }
}
'''

     // creating Frapid dir in Frapi
     Files.createDirectory( Paths.get( config.frapi.frapid ) ) 

     def path = Paths.get( config.frapid.classes + File.separator + "Frapid.php"  )
     destinationFolder = Paths.get( config.frapi.frapid + File.separator + "Frapid.php" )
     Files.copy( path, destinationFolder, REPLACE_EXISTING )

   }

   def unconfig() {

     // sostituisci main action
     def mainAction = Paths.get( config.frapid.templates + File.separator + "Main_bk.php" )
     def destinationFolder = Paths.get( config.frapi.main_controller + File.separator + "Main.php" )
     Files.copy( mainAction, destinationFolder, REPLACE_EXISTING )

     // deleting frapid dir in Frapi
     def frapidDir = new File( config.frapi.frapid )
      
     if( frapidDir.exists()  ) {
        frapidDir.eachFile { component ->
            Files.delete Paths.get( component.absolutePath )
        } 

        Files.delete( Paths.get( config.frapi.frapid )  )
     }
   }

   def camelize(String self) {
      self.split("_").collect() { it.substring(0, 1).toUpperCase() + it.substring(1, it.length()) }.join()
   }

   def getProjectRoot( path ) {
      
      path = Paths.get( path ).toRealPath()

      if( !Files.isDirectory( path ) ) throw new IllegalArgumentException( "Invalid Path, not a directory " )

      while ( Files.notExists( path.resolve(".frapid") ) ) {
         if( !path.parent ) throw new IllegalArgumentException( "No Frapid project found" )
         path = path.parent
      }

      return path
   }

   def buildActions() {

      // crea actions.xml e action cloni
      def routes = new XmlSlurper().parse("config/routes.xml")
      def writer = new FileWriter(config.frapi.config + File.separator + "actions.xml")
      def xml = new MarkupBuilder( writer )
 
      def names = [];

      xml.'frapi-config' {
	actions {
	   routes.route.each {
 	      def _route = it

	      action {	
 	         def _name =  "${_route.component}".replace('#', '_') 
		 names << _name	

 	         name( _name )
		 route( _route.url )
		 description()
		 'public'(1)
		 enabled(1)
		 parameters()
	      }
           }
	  
        }
      }

     writer.close()

     names.each { generate( 'front_controller', it ) }
     
      /*

UNDEPLOY
      new File( config.frapi.action ).eachFileMatch( ~/.*\.php/ ) { component ->
         Files.delete Paths.get( component.absolutePath )
      } 

      */


   }

}
