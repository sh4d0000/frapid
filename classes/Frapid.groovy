import java.nio.file.Paths 
//import java.nio.file.Path 
//import java.nio.file.Files

// import java.security.*

// import java.math.BigInteger;
import java.util.Date;
//import java.io.IOException


class Frapid {

    def config, serviceLocator
    def scaffolder, digitalSignature, projectManager, deployer
   
    def Frapid() {

        def frapidPath = System.getenv()["FRAPID_HOME"]
        config = new ConfigSlurper().parse( new File( "${frapidPath}/config.groovy" ).toURL() )

        serviceLocator = ServiceLocator.instance 
        deployer = serviceLocator.get 'deployer'
        projectManager = serviceLocator.get 'projectManager'
        scaffolder = serviceLocator.get 'scaffolder'
        digitalSignature = serviceLocator.get 'digitalSignature'

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
        scaffolder.generate( type, name, path )
    }
    
    def generateDoc( projectPath ) {
        scaffolder.generateDoc projectPath 
    }
    
    def generateRoutes( projectPath = '.' ) {
        scaffolder.generateRoutes projectPath
    }

    def generateKeys() {
	digitalSignature.generateKeys()
    }

    def verifyPack( pack, publicKey = null, signature = null ) {
        digitalSignature.verifyPack pack, publicKey, signature
    }

    def deploy( projectPath, environment = 'dev' ) {
	deployer.deploy projectPath, environment
    }

    def undeploy( projectPath, environment = 'dev' ) {
	deployer.undeploy projectPath, environment
    }
    
    def pack( path = ".", sign = true ) {
	deployer.pack path, sign
    }

    def unpack( file, path = "." ) {
	deployer.unpack file, path
    }

    def publish( pack, env = 'dev', destination = '/tmp/proj/'){
	deployer.publish pack, env, destination
    }

    def submit( username, projectPath = '.', env = 'prod' ) {
	deployer.submit username, projectPath, env
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

}
