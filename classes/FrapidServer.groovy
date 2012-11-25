import java.net.ServerSocket
import java.nio.file.Paths
import java.nio.file.Files

def server = new ServerSocket(4444)
 
while(true) {
    server.accept(true) { socket ->

        println "processing new connection..."
        socket.withStreams { input, output ->

            def reader = input.newReader()
            def buffer = reader.readLine()
            
            def command = buffer.tokenize()
            def operation = command[0]
            def args = command[ 1..-1 ]

            "$operation"( args, input, output )
            
        }
        println "processing/thread complete."

    }
}


def publish( args, input, output ) {

   def (env, packName, signatureName, sizePack, sizeSig) = args

   println env +' '+ packName +' '+ signatureName

   output << 'ok\n'
   output.flush()
   def packPath = receiveFile Paths.get( "../tmp/${packName}"), input, sizePack.toInteger() 

   output << 'ok\n'
   output.flush()
   def signaturePath = receiveFile Paths.get("../tmp/${signatureName}"), input, sizeSig.toInteger()
   
   def frapid = new Frapid()
   frapid.verifyPack( packPath.toString(), signaturePath.toString() )
   frapid.publish( packPath.toString() )

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
