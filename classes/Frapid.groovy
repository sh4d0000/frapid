import java.nio.file.Paths 
import java.nio.file.Files
import groovy.xml.MarkupBuilder
import java.security.*
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec
import java.security.spec.RSAPrivateKeySpec;
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

import sun.security.x509.*;
import java.security.cert.*;
import java.security.*;
import java.math.BigInteger;
import java.util.Date;
import java.io.IOException

import static java.nio.file.StandardCopyOption.*

class Frapid {

   def dirs, config
   
   def Frapid() {

      dirs = ["components", "lib", "config", "tmp", "media", "bin"];

      def frapidPath = System.getenv()["FRAPID_HOME"]
      config = new ConfigSlurper().parse(new File("${frapidPath}/config.groovy").toURL())
   }

   def generateKeys( ) {
      def path = config.frapid.home 
	
     
      //def keyGen = KeyPairGenerator.getInstance("DSA", "SUN")
      //keyGen.initialize(1024, SecureRandom.getInstance("SHA1PRNG", "SUN") );
      def keyGen = KeyPairGenerator.getInstance("RSA")
      keyGen.initialize(1024 );
      
      def keyPair = keyGen.generateKeyPair();
      def privateKey = keyPair.getPrivate();
      def publicKey = keyPair.getPublic();

      def pubKeyPath = Paths.get( path + File.separator + "public.key")
      def privKeyPath = Paths.get( path + File.separator + "private.key")

      Files.write( pubKeyPath, publicKey.encoded  )
      Files.write( privKeyPath, privateKey.encoded  )

   }


   def createProject( name, path = "." ) {

      def projectRoot = path + File.separator + name
      def projectRootPath = Paths.get projectRoot
      def toCreate = [ projectRoot ]

      toCreate += dirs.collect { projectRoot + File.separator + it } 
      toCreate.collect { Files.createDirectory( Paths.get( it ) ) }

      def templates = Paths.get config.frapid.templates

      // create routes.xml
      def routesXml = templates.resolve "routes.xml"
      def destination = projectRootPath.resolve "config/routes.xml"
      def file = routesXml.toFile()
      file.write( file.text.replaceAll("_appname_", name) )
      Files.copy( routesXml, destination )


      // create config.xml
      def configXml = templates.resolve "config.xml"
      destination = projectRootPath.resolve "config/config.xml"
      Files.copy( configXml, destination )

      // create deploy.xml
      def deployXml = templates.resolve "deploy.xml"
      destination = projectRootPath.resolve "config/deploy.xml"
      file = deployXml.toFile()
      file.write( file.text.replaceAll("_name_", name) )
      Files.copy( deployXml, destination )
     
      // create .frapid
      def frapidCheckFile = templates.resolve ".frapid"
      destination = projectRootPath.resolve ".frapid"
      Files.copy( frapidCheckFile, destination )

      // copy img
      def img = templates.resolve "default.jpg"
      destination = projectRootPath.resolve "media/default.jpg"
      Files.copy( img, destination )
    

      generate( "business_component", "SampleComponent", projectRoot )
      
      def home = Paths.get config.frapid.home
      if( Files.notExists( home.resolve( "public.key" ) ) || Files.notExists( home.resolve( "private.key") ) ) {
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

     def projectRoot = getProjectRoot projectPath
     undeploy( projectRoot.toString() )
     
     def app = new XmlSlurper().parse( projectRoot.resolve( "config/deploy.xml").toString() )

     def deployDir = Paths.get( config.frapi.frapid + File.separator + app.name )
     Files.createDirectory( deployDir ) 

     def path = projectRoot.resolve( "config/routes.xml" ) 
     def destinationFolder = deployDir.resolve( "routes.xml" )
     Files.copy( path, destinationFolder, REPLACE_EXISTING )

     path = projectRoot.resolve( "config/config.xml" ) 
     destinationFolder = deployDir.resolve( "config.xml" )
     Files.copy( path, destinationFolder, REPLACE_EXISTING )

     def componentsDir = projectRoot.resolve("components").toFile()
     componentsDir.eachFileMatch( ~/.*\.php/ ) { component ->

        path = Paths.get( component.absolutePath )
        destinationFolder = deployDir.resolve( component.name ) 
	    Files.copy( path, destinationFolder, REPLACE_EXISTING )

     }

     return deployDir

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

   def verifyPack( pack, signature, publicKey = null ) {

      pack = Paths.get pack
      signature = new File( signature ).bytes

      def pubKeyPath = Paths.get( config.frapid.home + File.separator + "public.key")
      if( publicKey ) pubKeyPath = Paths.get( publicKey )
      
      publicKey = getPublicKey pubKeyPath.toFile()
      def signatureManager = Signature.getInstance("SHA1withRSA");
      signatureManager.initVerify publicKey

      FileInputStream fis = new FileInputStream( pack.toFile()  );
      BufferedInputStream bufin = new BufferedInputStream(fis);
      byte[] buffer = new byte[1024];
      int len;
      while ((len = bufin.read(buffer)) >= 0) {
        signatureManager.update(buffer, 0, len)
      };
      bufin.close();

      signatureManager.verify signature
      

   }

   def pack( path = "." ) {

      path = getProjectRoot path

      def app = new XmlSlurper().parse( path.resolve( "config/deploy.xml").toString() )
      def ant = new AntBuilder()

      ant.project.getBuildListeners().each{ it.setOutputPrintStream(new PrintStream('/dev/null')) }

      def archivePath = path.resolve( "bin/${app.name}.tar.gz" ) 
      if ( Files.exists( archivePath) ) Files.delete archivePath 

      ant.tar( destFile: archivePath, compression: "gzip" ) {
         tarfileset ( dir: path , prefix: app.name )
      }

      // digitally sign data
      def pubKeyPath = Paths.get( config.frapid.home + File.separator + "public.key")
      def privKeyPath = Paths.get( config.frapid.home + File.separator + "private.key")
      
      def privKey =  getPrivateKey( privKeyPath.toFile() )
      def pubKey =  getPublicKey( pubKeyPath.toFile() )

      Signature dsa = Signature.getInstance("SHA1withRSA"); 
      dsa.initSign( privKey );

      FileInputStream fis = new FileInputStream( archivePath.toFile()  );
      BufferedInputStream bufin = new BufferedInputStream(fis);
      byte[] buffer = new byte[1024];
      int len;
      while ((len = bufin.read(buffer)) >= 0) {
        dsa.update(buffer, 0, len);
      };
      bufin.close();

      byte[] realSig = dsa.sign();


//      def cert = generateCertificate( 'CN=Test, L=London, C=GB',  privKey, pubKey, 365, 'DSA' )
     Files.write( path.resolve("bin/signature"), realSig)

   }

   def unpack( file, path = "." ){

      def ant = new AntBuilder()
      ant.project.getBuildListeners().each{ it.setOutputPrintStream(new PrintStream('/dev/null')) }
      ant.untar( src: file, dest: "${path}/", overwrite: true, compression: "gzip" )
 
   }

   def publish( pack, destination = '/tmp/proj/'){
     
      def ant = new AntBuilder()
      ant.project.getBuildListeners().each{ it.setOutputPrintStream(new PrintStream('/dev/null')) }
      ant.delete(dir: destination)
   
      unpack pack, destination
      
      def name = pack.split("\\.")[0]
      deploy( destination + name )

   }


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

 def getPrivateKey(File privateKeyFile) throws IOException, GeneralSecurityException {
    byte[] keyBytes = new byte[(int)privateKeyFile.length()];
    FileInputStream fis = new FileInputStream(privateKeyFile);
    fis.read(keyBytes);
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
    def privKey = (RSAPrivateKey) keyFactory.generatePrivate(spec);
    return privKey;
}

 def getPublicKey(File privateKeyFile) throws IOException, GeneralSecurityException {
    byte[] keyBytes = new byte[(int)privateKeyFile.length()];
    FileInputStream fis = new FileInputStream(privateKeyFile);
    fis.read(keyBytes);
    X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
    def pubKey = (RSAPublicKey) keyFactory.generatePublic(spec);
    return pubKey;
}
    def createPrivateKey( privateKeyPath ){

       def keyEncoded = Files.readAllBytes privateKeyPath

       def keyFactory = KeyFactory.getInstance( "RSA" );
       def keySpec = new RSAPrivateKeySpec(keyEncoded);
       return keyFactory.generatePrivate(keySpec);
                   
    }

/** 
 * Create a self-signed X.509 Certificate
 * @param dn the X.509 Distinguished Name, eg "CN=Test, L=London, C=GB"
 * @param pair the KeyPair
 * @param days how many days from now the Certificate is valid for
 * @param algorithm the signing algorithm, eg "SHA1withRSA"
 */ 
X509Certificate generateCertificate( dn, privKey, pubKey, days, algorithm)
  throws GeneralSecurityException, IOException
{
  X509CertInfo info = new X509CertInfo();
  Date from = new Date();
  Date to = new Date(from.getTime() + days * 86400000l);
  CertificateValidity interval = new CertificateValidity(from, to);
  BigInteger sn = new BigInteger(64, new SecureRandom());
  X500Name owner = new X500Name(dn);
 
  info.set(X509CertInfo.VALIDITY, interval);
  info.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(sn));
  info.set(X509CertInfo.SUBJECT, new CertificateSubjectName(owner));
  info.set(X509CertInfo.ISSUER, new CertificateIssuerName(owner));
  info.set(X509CertInfo.KEY, new CertificateX509Key(pubKey) );
  info.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3));
  AlgorithmId algo = new AlgorithmId(AlgorithmId.md5WithRSAEncryption_oid);
  info.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(algo));
 
  // Sign the cert to identify the algorithm that's used.
  X509CertImpl cert = new X509CertImpl(info);
  cert.sign(privKey, algorithm);
 
  // Update the algorith, and resign.
  algo = (AlgorithmId)cert.get(X509CertImpl.SIG_ALG);
  info.set(CertificateAlgorithmId.NAME + "." + CertificateAlgorithmId.ALGORITHM, algo);
  cert = new X509CertImpl(info);
  cert.sign(privKey, algorithm);
  return cert;
}   

}


