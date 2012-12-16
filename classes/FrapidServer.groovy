import java.net.ServerSocket
import java.nio.file.Paths
import java.nio.file.Files

def server = new ServerSocket(4444)

def frapidPath = System.getenv()["FRAPID_HOME"]
config = new ConfigSlurper().parse(new File("${frapidPath}/config.groovy").toURL())

println "Frapid server started"

while(true) {
    server.accept(true) { socket ->

        println "processing new connection..."
        socket.withStreams { input, output ->

            def reader = input.newReader()
            def buffer = reader.readLine()

            def command = buffer.tokenize()
            def operation = command[0]
            def args = command[ 1..-1 ]

            println 'operation: ' +command
            "$operation"( args, input, output )
            
        }
        println "processing/thread complete."

    }
}


def publish( args, input, output ) {

    def (env, packName, sizePack ) = args

    output << 'ok\n'
    output.flush()
    def packPath = receiveFile( Paths.get(config.frapid.tmp.toString(), packName), input, sizePack.toInteger() )

    def frapid = new Frapid()
    frapid.publish( packPath.toString() )

    output << 'bye\n'
    output.flush()
} 

def submit( args, input, output ) {
    
    def (env, packName, sizePack, username) = args
    println "start submit"

    output << 'ok\n'
    output.flush()
    def packPath = receiveFile( Paths.get(config.frapid.tmp.toString(), packName), input, sizePack.toInteger() )

    println "file ricevuti"
   
    def frapid = new Frapid()
    def pubKeyPath = frapid.getPublicKeyBy username
        
    if( !pubKeyPath ) {
        output << 'Cannot find public key\n'
        return;
    } else if( !frapid.verifyPack( packPath.toString(), null, pubKeyPath.toString() ) ) {
        output << 'Not valid signature\n'
        return;
    }
    
    println "saveinstore invocation" 
    frapid.saveApiIntoStore( packPath, username )

    output << 'bye\n'
    output.flush()    
}

def checkName( args, input, output ) {
    
    def (name) = args
    
    def frapid = new Frapid()
    def response = frapid.isProjectNameAvailable( name )? 'ok\n' : 'ko\n'
    
    output << response
    output << 'bye\n'
    output.flush()
    
}

def receiveFile( path, input, size) {
    
    Files.deleteIfExists(path)

    def buffer = new byte[size] 
    def output = path.toFile().newOutputStream() 

    while( size > 0) {
        def nBytes = input.read( buffer )
        size -= nBytes
    
        if( nBytes < 0 ) throw new Exception('Something gone bad') 
        output.write( buffer, 0, nBytes ) 
    }
    
    output.flush()
	
    return path.toRealPath()
}
