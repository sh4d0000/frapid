import java.nio.file.Paths 
import java.nio.file.Path 
import java.nio.file.Files

import java.security.*

import java.math.BigInteger;
import java.util.Date;
import java.io.IOException

import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerFactory
import javax.xml.transform.OutputKeys

class Frapid {

    def config, serviceLocator
    def scaffolder, digitalSignature, projectManager, deployer
   
    def Frapid() {


        def frapidPath = System.getenv()["FRAPID_HOME"]
        config = new ConfigSlurper().parse( new File( "${frapidPath}/config.groovy" ).toURL() )

        serviceLocator = new ServiceLocator()
        scaffolder = serviceLocator.get 'scaffolder'
        digitalSignature = serviceLocator.get 'digitalSignature'
        projectManager = serviceLocator.get 'projectManager'

    }

    def createProject( name, path = ".", force = false ) {
        projectManager.createProject name, path, force
    }

    def checkName( name = null, path = "."  ) {
        projectManager.checkName name, path
    }
    
    def isProjectNameAvailable( name ) {
        projectManager.isProjectNameAvailable name
    }
    
    def rename( name, path = '.' ) {
        projectManager.rename name, path
    }
    
    def generate( type, name, path = "." ) {
        path = projectManager.getProjectRoot path

        return scaffolder.generate( type, name, path )

    }
    
    def generateDoc( projectPath ) {
        def projectRoot = projectManager.getProjectRoot projectPath
        
        scaffolder.generateDoc projectRoot 
    }
    
    def generateRoutes( projectPath = '.' ) {
        def projectRoot = projectManager.getProjectRoot projectPath

        scaffolder.generateRoutes projectRoot
        
    }

    def verifyPack( pack, publicKey = null, signature = null ) {
        digitalSignature.verifyPack pack, publicKey, signature
    }

    def deploy( projectPath, environment = 'dev' ) {

        if( config.envs."$environment".type == 'remote' ) {
            return remoteDeploy( projectPath, environment )
        } 
        
        def projectRoot = projectManager.getProjectRoot projectPath
        def configDir = projectRoot.resolve "config" 
        undeploy projectRoot        
        
        generateRoutes projectRoot 
        generateDoc projectRoot 
        
        def app = new XmlSlurper().parse( configDir.resolve( "deploy.xml").toString() )

        def deployDir = createDir( config.envs."$environment".frapi.frapid + File.separator + app.name )
        def componentsDeploy = createDir( deployDir.resolve('components') )
        serviceLocator.fileSystem { copy configDir, deployDir, 'routes.xml', 'config.xml' }
        
        def libDeploy = createDir deployDir.resolve('lib')
        projectRoot.resolve('lib').toFile().eachFileMatch( ~/.*\.php/ ) { lib ->
            serviceLocator.fileSystem { copy lib.absolutePath, libDeploy.resolve( lib.name ) }
        }
               
        projectRoot.resolve("components").toFile().eachFileMatch( ~/.*\.php/ ) { component ->
            serviceLocator.fileSystem { copy component.absolutePath, componentsDeploy.resolve( component.name ) }
        }
        
        return deployDir

    }
    
    def remoteDeploy( projectPath, env ) {

        def projectRoot = projectManager.getProjectRoot projectPath
        def app = new XmlSlurper().parse( projectRoot.resolve( "config/deploy.xml").toString() )
        pack( projectPath, false )
        
        def uri = config.envs."$env".uri.split(':')
        def s = new Socket( uri[0], uri[1].toInteger() );

        s.withStreams { input, output ->
            
            def packPath = projectRoot.resolve( "dist/${app.name}_api.tar.gz" )
            def sizePack = Files.size(packPath)  

            output << "publish dev ${app.name}_api.tar.gz $sizePack\n"

            def reader = input.newReader()
            def buffer = reader.readLine()
            if(buffer == 'ok') {
                sendFile( packPath , input, output ) 
            }

            buffer = reader.readLine()
            if(buffer == 'bye') {
                //println "Terminato"
            }
        }

        return true
    }
    
    def sendFile( path, input, output ) {
        
        output.write( path.toFile().bytes )
        output.flush();
        
    }

    def undeploy( projectPath, environment = 'dev' ) {

        def projectRoot = projectManager.getProjectRoot projectPath
        
        def app = new XmlSlurper().parse( projectRoot.resolve( "config/deploy.xml").toString() )
        def deployDir = Paths.get( config.envs."$environment".frapi.frapid , (String) app.name ).toFile()
      
        deployDir.deleteDir()
        
    }

    def pack( path = ".", sign = true ) {

        path = projectManager.getProjectRoot path

        def app = new XmlSlurper().parse( path.resolve( "config/deploy.xml").toString() )
        def ant = new AntBuilder()

        ant.project.getBuildListeners().each{ it.setOutputPrintStream(new PrintStream('/dev/null')) }
        def projectPath = path.resolve( "tmp/${app.name}.tar.gz" ) 
        def sigPath = path.resolve("tmp/${app.name}.sig")
        
        Files.deleteIfExists projectPath  

        ant.tar( destFile: projectPath , compression: "gzip" ) {
            tarfileset ( dir: path , prefix: app.name )
        }

        if( sign ) {
            // digitally sign data
            def pubKeyPath = Paths.get config.frapid.keyDir , "public.key"
            def privKeyPath = Paths.get config.frapid.keyDir , "private.key"

            def privKey = digitalSignature.getPrivateKey( privKeyPath.toFile() )
            def pubKey =  digitalSignature.getPublicKey( pubKeyPath.toFile() )

            Signature dsa = Signature.getInstance("SHA1withRSA"); 
            dsa.initSign( privKey );

            FileInputStream fis = new FileInputStream( projectPath.toFile()  );
            BufferedInputStream bufin = new BufferedInputStream(fis);
            byte[] buffer = new byte[1024];
            int len;
            while ((len = bufin.read(buffer)) >= 0) {
                dsa.update(buffer, 0, len);
            };
            bufin.close();

            byte[] realSig = dsa.sign();

            Files.write( sigPath, realSig)
        }
        
        def archivePath = path.resolve( "dist/${app.name}_api.tar.gz" ) 
        Files.deleteIfExists archivePath 

        ant.tar( destFile: archivePath, compression: "gzip", basedir: path.resolve('tmp') ) 

        // cancellazione file temporanei
        Files.delete projectPath
        Files.deleteIfExists sigPath  
    }

    def unpack( file, path = "." ){

        def tmp = Paths.get path
                       
        if( !Files.exists(tmp) ) {
            serviceLocator.fileSystem { createDir tmp }
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
        
        pack = Paths.get pack
        destination = Paths.get destination
        def name = pack.fileName.toString().split("_api")[0]
        
        unpack pack.toString(), destination.toString()
        unpack destination.resolve( "${name}.tar.gz" ), destination.toString()
        
        deploy( destination.resolve("$name").toString(), env )

    }
    
    def submit( username, projectPath = '.', env = 'prod' ) {
        
        def projectRoot = projectManager.getProjectRoot projectPath
        def app = new XmlSlurper().parse( projectRoot.resolve( "config/deploy.xml").toString() )
        
        if( !checkName( app.name ) ) {
            println 'There is yet a project with this name into the store. Please change the name of your project'
            return false
        }
        
        pack projectPath 
        
        def uri = config.envs."$env".uri.split(':')
        def s = new Socket( uri[0], uri[1].toInteger() );

        s.withStreams { input, output ->
            
            def packPath = projectRoot.resolve( "dist/${app.name}_api.tar.gz" )
            def sizePack = Files.size(packPath)  

            output << "submit dev ${app.name}_api.tar.gz $sizePack $username\n"

            def reader = input.newReader()
            def buffer = reader.readLine()
            if(buffer == 'ok') {
                sendFile( packPath , input, output ) 
            }

            // TODO spostare output sul comando frapid-deploy
            buffer = reader.readLine()
            if(buffer == 'bye') {
                //println "Terminato"
                println "Project Submitted"
            } else if( buffer == 'Not valid signature' ) {
                // TODO creare una exception apposita
                println 'Not valid signature'
                return false
            } else if( buffer == 'Cannot find public key' ) {
                // TODO creare una exception apposita
                println 'Is impossible to recover your public key. Please check your username or set a public key into the store\n'
                return false
            }
        }
        
        return true
        
    }
    
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
        
        def projectName = packFile.name.split('_api')[0]
        
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
        serviceLocator.fileSystem {
            copy templatesDir, frapiConf.main_controller, 'Main.php'

            // moving front controller e actions.xml
            copy templatesDir, frapiConf.action, "Frontcontroller.php"
            copy templatesDir, frapiConf.config, "actions.xml"
        }



        Paths.get( frapiConf.custom, "AllFiles.php" ).toFile() << '''
$it = new RecursiveDirectoryIterator( CUSTOM_PATH . DIRECTORY_SEPARATOR . 'frapid' ); 

foreach (new RecursiveIteratorIterator( $it ) as $fileInfo) {
  if($fileInfo->getExtension() == "php") {
     require_once $fileInfo->getPathname();
  }
}
'''

        // creating Frapid dir in Frapi
        serviceLocator.fileSystem {
            createDir frapiConf.frapid
            copy config.frapid.classes, frapiConf.frapid , "Frapid.php"
        }

        
        def p = serviceLocator.fileSystem { copy config.frapid.home, frapiConf.frapid, config.frapid.frapiConfigFile }

    }

    def unconfig( environment = 'dev') {

        def workingDir = new File(config.envs."$environment".frapi.home)
        "git add .".execute(null, workingDir).waitFor()
        "git reset --hard".execute( null, workingDir )
        
        def frapidDir = Paths.get( config.envs."$environment".frapi.frapid ).toFile()
        frapidDir.deleteDir()

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
}
