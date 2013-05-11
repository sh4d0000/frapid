import java.security.*
import java.nio.file.Paths 
import java.nio.file.Files

import java.nio.file.attribute.PosixFilePermissions

class DeployerService {

    def config, serviceLocator, scaffolder, projectManager, digitalSignature

    def DeployerService() {

       def frapidPath = System.getenv()["FRAPID_HOME"]
       config = new ConfigSlurper().parse( new File( "${frapidPath}/config.groovy" ).toURL() )
  
       serviceLocator = ServiceLocator.instance
       projectManager = serviceLocator.get 'projectManager'
       scaffolder = serviceLocator.get 'scaffolder'
       digitalSignature = serviceLocator.get 'digitalSignature'

    }

    def deploy( projectPath, environment = 'dev' ) {

        if( config.envs."$environment".type == 'remote' ) {
            return remoteDeploy( projectPath, environment )
        } 
        
        def projectRoot = projectManager.getProjectRoot projectPath
        def configDir = projectRoot.resolve "config" 
        undeploy projectRoot        
        
        scaffolder.generateRoutes projectRoot 
        scaffolder.generateDoc projectRoot 
        
        def app = new XmlSlurper().parse( configDir.resolve( "deploy.xml").toString() )
	def deployDir

        serviceLocator.fileSystem {
 	   
            deployDir = createDir( config.envs."$environment".frapi.frapid + File.separator + app.name )
            def componentsDeploy = createDir( deployDir.resolve('components') )
	    copy configDir, deployDir, 'routes.xml', 'config.xml'
            def libDeploy = createDir deployDir.resolve('lib')

        
            projectRoot.resolve('lib').toFile().eachFileMatch( ~/.*\.php/ ) { lib ->
                copy lib.absolutePath, libDeploy.resolve( lib.name ) 
            }
               
            projectRoot.resolve("components").toFile().eachFileMatch( ~/.*\.php/ ) { component ->
                 copy component.absolutePath, componentsDeploy.resolve( component.name )
            }

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

}
