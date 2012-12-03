import java.nio.file.Paths 
import java.nio.file.Path 
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
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.FileAttribute

import groovy.xml.StreamingMarkupBuilder
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerFactory
import javax.xml.transform.OutputKeys

class Frapid {

    def dirs, config
   
    def Frapid() {

        dirs = ["components", "lib", "config", "tmp", "media", "dist", "docs"];

        def frapidPath = System.getenv()["FRAPID_HOME"]
        config = new ConfigSlurper().parse( new File( "${frapidPath}/config.groovy" ).toURL() )
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

        def keyDir = Paths.get config.frapid.keyDir 
        if( !Files.exists(keyDir) ) {
            this.createDir keyDir  
        }   
        
        def pubKeyPath = Paths.get config.frapid.keyDir , "public.key"
        def privKeyPath = Paths.get config.frapid.keyDir ,"private.key"
        
        Files.write( pubKeyPath, publicKey.encoded  )
        Files.write( privKeyPath, privateKey.encoded  )

    }
        
    def createProject( name, path = ".", force = false ) {

        if( !force && !checkName(name) ) {
            return false
        }
        
        def projectRootPath = Paths.get path, name
        def toCreate = [ projectRootPath ]

        toCreate += dirs.collect { projectRootPath.resolve it } 
        toCreate.each { this.createDir it, false  }

        def templatesDir = Paths.get config.frapid.templates
        def configDir = projectRootPath.resolve 'config'
        def mediaDir = projectRootPath.resolve 'media'

        // create routes.xml
        def file = this.copy( templatesDir, configDir, 'routes.xml' ).toFile() 
        file.write( file.text.replaceAll("_appname_", name) )
        
        // create deploy.xml
        file = this.copy( templatesDir, configDir, 'deploy.xml').toFile()
        file.write( file.text.replaceAll("_name_", name) )
        
        // create config.xml .frapid and default.jpg
        this.copy templatesDir, configDir, 'config.xml'
        this.copy templatesDir, projectRootPath, '.frapid'
        this.copy templatesDir, mediaDir, 'default.jpg'
        
        generate( "business_component", "SampleComponent", projectRootPath )
      
        def keyDir = Paths.get config.frapid.keyDir 
        if( Files.notExists( keyDir.resolve( "public.key" ) ) || Files.notExists( keyDir.resolve( "private.key") ) ) {
            generateKeys()
        }

        return true
    
    }
     
    def checkName( name = null, path = "."  ) {
        
        if( !name ) {
            def projectRoot = getProjectRoot path
            def app = new XmlSlurper().parse( projectRoot.resolve( "config/deploy.xml").toString() )
            name = app.name
        }
        
        def nameAvailable = false
        
        def uri = config.envs.prod.uri.split(':')
        def s = new Socket( uri[0], uri[1].toInteger() );
        
        s.withStreams { input, output ->
            
            output << "checkName $name\n"

            def reader = input.newReader()
            def buffer = reader.readLine()
            
            nameAvailable = buffer=='ok'? true :false
                                     
            buffer = reader.readLine()
            if(buffer == 'bye') {
                //println "Terminato"
            }
        }
        
        return nameAvailable
    }
    
    def isProjectNameAvailable( name ) {
        
        def frapiConfPath = Paths.get( config.frapid.home, config.frapid.frapiConfigFile )
        
        def frapiConf = new XmlSlurper().parse( frapiConfPath.toString() )
        def db = frapiConf.database
        
        def driverURL = new File( config.frapid.mysqlDriver ).toURL()
        this.getClass().classLoader.rootLoader.addURL( driverURL )
        
        def sql = groovy.sql.Sql.newInstance("jdbc:mysql://$db.url", db.username.toString(), db.password.toString() )
        
        def answer = 0
        sql.eachRow("select count(*) from api where name = $name") { row ->
            answer = row[0]
        }
        
        return answer == 0? true : false     
        
    }
    
    def rename( name, path = '.' ) {
        
        def projectRoot = getProjectRoot path
        undeploy projectRoot  
        
        def deployXMLPath = projectRoot.resolve("config/deploy.xml")
        def deployXML = new XmlSlurper().parse( deployXMLPath.toString() )
        
        deployXML.name = "$name"

        def outputBuilder = new StreamingMarkupBuilder()
        String result = indentXml( outputBuilder.bind{ mkp.yield deployXML } )
        
        new File(deployXMLPath.toString()).text = result
        
        generateRoutes()
        projectRoot.toFile().renameTo( projectRoot.getParent().resolve("$name").toString() ) 
        
    }
    
    def String indentXml(xml) {
        def factory = TransformerFactory.newInstance()
        factory.setAttribute("indent-number", 3);

        Transformer transformer = factory.newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, 'yes')
        StreamResult result = new StreamResult(new StringWriter())
        transformer.transform(new StreamSource(new ByteArrayInputStream(xml.toString().bytes)), result)
        return result.writer.toString()
    }

    def generate( type, name, path = "." ) {

        path = getProjectRoot path
        def app = new XmlSlurper().parse( path.resolve( "config/deploy.xml").toString() )
                
        def templatesDir = Paths.get config.frapid.templates 
        def componentsDir = path.resolve( "components" )
        
        if( type == "business_component" ) {

            def file = this.copy( templatesDir, componentsDir, camelize(type) + ".php").toFile()
            file.write( file.text.replaceAll("_${type}_", name) )
            file.write( file.text.replaceAll("_namespace_", app.name.toString() ) )
            file.renameTo( componentsDir.toString() + File.separator + "${name}.php" )
                        
        } 

    }
    
    def generateDoc( projectPath ) {
        def projectRoot = getProjectRoot projectPath
        
        def ant = new AntBuilder()
        ant.project.getBuildListeners().each{ it.setOutputPrintStream(new PrintStream('/dev/null')) }
        
        """phpdoc -d ${projectRoot.toString()}/components/ -t ${projectRoot.toString()}/docs/""".execute()
        
        ant.tar( destFile: "${projectRoot.toString()}/dist/docs.tar.gz", compression: "gzip" ) {
            tarfileset ( dir: "${projectRoot.toString()}/docs/" , prefix: "docs" )
        }

        
    }
    
    def generateRoutes( projectPath = '.' ) {
        def projectRoot = getProjectRoot projectPath
        
        def app = new XmlSlurper().parse( projectRoot.resolve( "config/deploy.xml").toString() )
        
        def routesXml = projectRoot.resolve "config/routes.xml"
        if( Files.exists( routesXml )) {
            Files.delete routesXml
        }
        
        def routeList = []
        
        projectRoot.resolve("components").toFile().eachFileMatch( ~/.*\.php/ ) { component ->
	    def componentName = component.name.split("\\.")[0]
            
            def phpDocOpen = false
            def phpDocClose = false
            
            def phpDocBlocks = []
            def newBlock = null
           
            
            component.eachLine() {
                
                if( it.contains("/*") && !phpDocOpen ) {
                    
                    phpDocOpen = true
                    newBlock = []
                    newBlock << it
                    
                } else if ( !it.contains("*/") && phpDocOpen ) { 
                    
                    newBlock << it
                    
                } else if ( it.contains("*/") && phpDocOpen ) { 
                    
                    phpDocOpen = false
                    phpDocClose = true
                    newBlock << it
                    
                } else if ( phpDocClose ) { 
                    
                    // recupera la riga successiva la chiusura del blocco quindi la dichiarazione della funzione
                    phpDocClose = false
                    newBlock << it
                    phpDocBlocks << newBlock
                                                        
                }
                
            }
            
            phpDocBlocks.each() { docBlock ->
            
                def route = [component:componentName]
                
                docBlock.each { line ->
                    if( line.contains("@frapid-url") ) {
                        route.url = line.split("@frapid-url")[1].trim()
                    } else if( line.contains("@frapid-method") ) {
                        route.httpMethod = line.split("@frapid-method")[1].trim()
                    }
                }
                
                route.function = docBlock.last().split("function")[1].split("\\(")[0].trim()
                
                routeList << route
                
            }
            
        }
        
        // Creazione routes.xml
        routesXml.toFile().withWriter { writer ->
            
            def xml = new MarkupBuilder(writer)
            xml."routes-config"() {
                
                root( app.name )                
                routes() {
                    routeList.each { element ->
                        route() {
                            url( element.url )
                            component( "$element.component#$element.function" ) 
                            method( element.httpMethod )
                        }
                    }
                } 
                
            }
                                   
        }
        
        
        
    }

    def deploy( projectPath, environment = 'dev' ) {

        if( config.envs."$environment".type == 'remote' ) {
            return remoteDeploy( projectPath, environment )
        } 
        
        def projectRoot = getProjectRoot projectPath
        def configDir = projectRoot.resolve "config" 
        undeploy projectRoot        
        
        generateRoutes projectRoot 
        generateDoc projectRoot 
        
        def app = new XmlSlurper().parse( configDir.resolve( "deploy.xml").toString() )

        def deployDir = createDir( config.envs."$environment".frapi.frapid + File.separator + app.name )
        def componentsDeploy = createDir( deployDir.resolve('components') )
        this.copy configDir, deployDir, 'routes.xml', 'config.xml'
        
        def libDeploy = createDir deployDir.resolve('lib')
        projectRoot.resolve('lib').toFile().eachFileMatch( ~/.*\.php/ ) { lib ->
            this.copy lib.absolutePath, libDeploy.resolve( lib.name ) 
        }
               
        projectRoot.resolve("components").toFile().eachFileMatch( ~/.*\.php/ ) { component ->
            this.copy component.absolutePath, componentsDeploy.resolve( component.name ) 
        }
        
        return deployDir

    }
    
    def remoteDeploy( projectPath, env ) {

        def projectRoot = getProjectRoot projectPath
        def app = new XmlSlurper().parse( projectRoot.resolve( "config/deploy.xml").toString() )
        pack( projectPath )
        
        def uri = config.envs."$env".uri.split(':')
        def s = new Socket( uri[0], uri[1].toInteger() );

        s.withStreams { input, output ->
            
            def packPath = projectRoot.resolve( "dist/${app.name}.tar.gz" )
            def sigPath = projectRoot.resolve( "dist/${app.name}.sig" )
            def sizePack = Files.size(packPath)  
            def sizeSig = Files.size(sigPath)  

            output << "publish dev ${app.name}.tar.gz ${app.name}.sig $sizePack $sizeSig\n"

            def reader = input.newReader()
            def buffer = reader.readLine()
            if(buffer == 'ok') {
                sendFile( packPath , input, output ) 
            }

            buffer = reader.readLine()
            if(buffer == 'ok') {
                sendFile( sigPath, input, output ) 
            }
  
            buffer = reader.readLine()
            if(buffer == 'bye') {
                //println "Terminato"
            }
        }

        return true
    }
    
    def sendFile( path, input, output ) {
        println 'invio file: '+ path.toString() 
        println 'invio in corso'

        output.write( path.toFile().bytes )
        output.flush();

        println("File inviato correttamente");
			
    }


    def undeploy( projectPath, environment = 'dev' ) {

        def projectRoot = getProjectRoot projectPath
        
        def app = new XmlSlurper().parse( projectRoot.resolve( "config/deploy.xml").toString() )
        def deployDir = Paths.get( config.envs."$environment".frapi.frapid , (String) app.name ).toFile()
      
        deployDir.deleteDir()
        
    }

    def verifyPack( pack, signature, publicKey = null ) {

        pack = Paths.get pack
        signature = new File( signature ).bytes

        def pubKeyPath = Paths.get( config.frapid.keyDir , "public.key")
        if( publicKey ) pubKeyPath = Paths.get( publicKey )
      
        publicKey = getPublicKey pubKeyPath.toFile()
        def signatureManager = Signature.getInstance("SHA1withRSA");
        signatureManager.initVerify publicKey

        FileInputStream fis = new FileInputStream( pack.toFile() );
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
        def archivePath = path.resolve( "dist/${app.name}.tar.gz" ) 
        
        if ( Files.exists(archivePath) ) {
            Files.delete archivePath 
        } 

        ant.tar( destFile: archivePath, compression: "gzip" ) {
            tarfileset ( dir: path , prefix: app.name )
        }

        // digitally sign data
        def pubKeyPath = Paths.get config.frapid.keyDir , "public.key"
        def privKeyPath = Paths.get config.frapid.keyDir , "private.key"
      
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

        Files.write( path.resolve("dist/${app.name}.sig"), realSig)

    }

    def unpack( file, path = "." ){

        def tmp = Paths.get( path )
                       
        if( !Files.exists(tmp) ) {
            this.createDir tmp  
        }           
                               
        def ant = new AntBuilder()
        ant.project.getBuildListeners().each{ it.setOutputPrintStream(new PrintStream('/dev/null')) }
        ant.untar( src: file, dest: "${path}/", overwrite: true, compression: "gzip" )
        
        def perms = PosixFilePermissions.fromString("rwxrwxrwx")
        def attr = PosixFilePermissions.asFileAttribute(perms)
        
        tmp.toFile().eachFileRecurse {
            
            try {
                Files.setPosixFilePermissions( Paths.get(it.toURI()) , perms )
            } catch(e) {
                //println "catched"
            }
        }
 
    }

    def publish( pack, env = 'dev', destination = '/tmp/proj/'){
     
        def ant = new AntBuilder()
        ant.project.getBuildListeners().each{ it.setOutputPrintStream(new PrintStream('/dev/null')) }
        
        def dir = new File( destination )
        def path = Paths.get pack
        
        unpack path.toString(), destination
        def name = path.fileName.toString().split("\\.")[0]
        
        deploy( destination + name, env )

    }
    
    def submit( username, projectPath = '.', env = 'prod' ) {
        
        println username
        
        def projectRoot = getProjectRoot projectPath
        def app = new XmlSlurper().parse( projectRoot.resolve( "config/deploy.xml").toString() )
        
        if( !checkName( app.name ) ) {
            println 'There is yet a project with this name into the store. Please change the name of your project'
            return false
        }
        
        pack( projectPath )
        
        def uri = config.envs."$env".uri.split(':')
        def s = new Socket( uri[0], uri[1].toInteger() );

        s.withStreams { input, output ->
            
            def packPath = projectRoot.resolve( "dist/${app.name}.tar.gz" )
            def sigPath = projectRoot.resolve( "dist/${app.name}.sig" )
            def sizePack = Files.size(packPath)  
            def sizeSig = Files.size(sigPath)  

            output << "submit dev ${app.name}.tar.gz ${app.name}.sig $sizePack $sizeSig $username\n"

            def reader = input.newReader()
            def buffer = reader.readLine()
            if(buffer == 'ok') {
                sendFile( packPath , input, output ) 
            }

            buffer = reader.readLine()
            if(buffer == 'ok') {
                sendFile( sigPath, input, output ) 
            }
           
            println "dopo invio file"
            buffer = reader.readLine()
            if(buffer == 'bye') {
                println "ricevuto bye"
                //println "Terminato"
            } else if( buffer == 'Not valid signature' ) {
                // TODO creare una exception apposita
                println 'Not valid signature'
            } else if( buffer == 'Cannot find public key' ) {
                // TODO creare una exception apposita
                println 'Is impossible to recover your public key. Please check your username or set a public key into the store\n'
            }
        }
        
        println "terminato"

        return true
        
    }
    
    
    // TODO da terminare implementazione
    def saveApiIntoStore( packPath, username ) {
        
        // sposta file in private dir
        packPath = copy packPath.parent, config.drupal.privateFileSystem.toString(), packPath.toFile().name
        def packFile = packPath.toFile()
        
        def frapiConfPath = Paths.get( config.frapid.home, config.frapid.frapiConfigFile )
        
        def frapiConf = new XmlSlurper().parse( frapiConfPath.toString() )
        def db = frapiConf.database
        
        def driverURL = new File( config.frapid.mysqlDriver ).toURL()
        this.getClass().classLoader.rootLoader.addURL( driverURL )
        
        def sql = groovy.sql.Sql.newInstance("jdbc:mysql://$db.url", db.username.toString(), db.password.toString() )
        
        def userId = getUserIdBy username, sql
        def uri = new String("private://$packFile.name".getBytes(), "UTF-8")
        def currentTime = new java.sql.Date( System.currentTimeMillis() ) 
        
        // la tabella ha come timestamp un long di 10, ja
        currentTime = ((currentTime.time / 1000).toLong())
        
        def record = [
            uid: userId, 
            filename: packFile.name, 
            uri: uri, 
            filemime: 'application/octet-stream', 
            filesize: packFile.size(), 
            status: 1, 
            timestamp: currentTime
        ]
        
        def insertSql = """
insert into file_managed( uid, filename, uri, filemime, filesize, status, timestamp)
values( :uid, :filename, :uri, :filemime, :filesize, :status, :timestamp )
"""
        // salva file in managed file table
        def recordInserted = sql.executeInsert( insertSql, record)
        
        def projectName = packFile.name.split('\\.')[0]
        println projectName
        
        // salva api in api table
        record = [
        name: projectName, 
        user_id: userId, 
        status: 'PENDING', 
        fid: recordInserted[0][0],
        sku: projectName
        ]
        
        def api = sql.dataSet( 'api' )
        api.add record 
    }
    
    def getPublicKeyBy( username ) {
        
        def frapiConfPath = Paths.get( config.frapid.home, config.frapid.frapiConfigFile )
        
        def frapiConf = new XmlSlurper().parse( frapiConfPath.toString() )
        def db = frapiConf.database
        
        def driverURL = new File( config.frapid.mysqlDriver ).toURL()
        this.getClass().classLoader.rootLoader.addURL( driverURL )
        
        def sql = groovy.sql.Sql.newInstance("jdbc:mysql://$db.url", db.username.toString(), db.password.toString() )
        
        def keyPath
        sql.eachRow("""SELECT f.uri 
FROM users u, apiuser p, file_managed f
WHERE p.public_key = f.fid
AND u.uid = p.user_id
AND u.name = $username""") { row ->
            keyPath = row[0].split('public://')[1]
        }
        
        
        return keyPath? Paths.get( config.drupal.publicFileSystem, keyPath ) : false
        
    }
    
    def getUserIdBy( username, sql ) {
        
        def userId
        sql.eachRow("SELECT uid FROM users WHERE name = $username") { row ->
            userId = row[0]
        }
        
        return userId
        
    }


    def config( environment = 'dev') {
        
        def frapiConf = config.envs."$environment".frapi
        
        // sostituisci main controller
        def templatesDir = Paths.get config.frapid.templates 
        this.copy templatesDir, frapiConf.main_controller, 'Main.php' 

        // moving front controller e actions.xml
        this.copy templatesDir, frapiConf.action, "Frontcontroller.php"
        this.copy templatesDir, frapiConf.config, "actions.xml"

        Paths.get( frapiConf.custom, "AllFiles.php" ).toFile() << '''
$it = new RecursiveDirectoryIterator( CUSTOM_PATH . DIRECTORY_SEPARATOR . 'frapid' ); 

foreach (new RecursiveIteratorIterator( $it ) as $fileInfo) {
  if($fileInfo->getExtension() == "php") {
     require_once $fileInfo->getPathname();
  }
}
'''

        // creating Frapid dir in Frapi
        this.createDir frapiConf.frapid 
        this.copy config.frapid.classes, frapiConf.frapid , "Frapid.php"
        
        def p = this.copy config.frapid.home, frapiConf.frapid, config.frapid.frapiConfigFile

    }

    def unconfig( environment = 'dev') {

        def workingDir = new File(config.envs."$environment".frapi.home)
      "git add .".execute(null, workingDir).waitFor()
      "git reset --hard".execute( null, workingDir )

    }

    def camelize(String self) {
        self.split("_").collect() { it.substring(0, 1).toUpperCase() + it.substring(1, it.length()) }.join()
    }

    def getProjectRoot( path ) {
      
        path = Path.class.isInstance(path)? path : Paths.get( path.toString() ).toRealPath()
                
        if( !Files.isDirectory( path ) ) throw new IllegalArgumentException( "Invalid Path, not a directory " )

        while ( Files.notExists( path.resolve(".frapid") ) ) {
            if( !path.parent ) throw new IllegalArgumentException( "No Frapid project found" )
            path = path.parent
        }

        return path
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


    def generateCertificate( dn, privKey, pubKey, days, algorithm) {
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
    
    def copy(from, to, String... fileNames = null ) {
    
        def perms = PosixFilePermissions.fromString "rwxrwxrwx"
        def attr  = PosixFilePermissions.asFileAttribute perms
        def fileCopied = null
        
        def fromPath = Path.class.isInstance(from)? from : Paths.get(from.toString())
        def toPath   = Path.class.isInstance(to)  ? to   : Paths.get(to.toString())
            
        if( fileNames == null ) {
            
            fileCopied = Files.copy fromPath, toPath, REPLACE_EXISTING, COPY_ATTRIBUTES  
            Files.setPosixFilePermissions fileCopied, perms
            
            return fileCopied
                          
        } else {
        
            def filesCopied = fileNames.collect { fileName ->
                                    
                fileCopied = Files.copy fromPath.resolve( fileName ), toPath.resolve( fileName ), REPLACE_EXISTING, COPY_ATTRIBUTES  
                Files.setPosixFilePermissions fileCopied, perms
        
                fileCopied
            }
            
            return filesCopied.size() == 1? filesCopied[0] : filesCopied 
        }
        
        return toPath
    }
    
    def createDir( dir, defaultPermission = true ) {
                                      
        def dirPath = Path.class.isInstance(dir)? dir : Paths.get( dir.toString() )
        Files.createDirectory dirPath 
        
        if( defaultPermission ) {
            def perms = PosixFilePermissions.fromString 'rwxrwxrwx'
            def attr = PosixFilePermissions.asFileAttribute perms
            Files.setPosixFilePermissions dirPath, perms
        }
        
        return dirPath
    }
  
}
