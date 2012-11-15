import java.nio.file.Paths
import java.nio.file.Files

def (env, pack, sig) = args

s = new Socket("localhost", 4444);

s.withStreams { input, output ->
  println "$env $pack $sig"

  def packPath = Paths.get( pack )
  def sigPath = Paths.get( sig )
  def sizePack = Files.size(packPath)  
  def sizeSig = Files.size(sigPath)  

  output << "publish prod p1.tar.gz p1.signature $sizePack $sizeSig\n"

  println "attendo ok"
  def reader = input.newReader()
  buffer = reader.readLine()
  if(buffer == 'ok') {
     sendFile( packPath , input, output ) 
  }

  println "attendo ok"
  buffer = reader.readLine()
  if(buffer == 'ok') {
     sendFile( Paths.get( sig ), input, output ) 
  }
  
  
  println "attendo bye"
  buffer = reader.readLine()
  if(buffer == 'bye') {
    println "Terminato"
  }
}

def sendFile( path, input, output ) {
    println 'invio file: '+ path.toString() 
    println 'invio in corso'

    output.write( path.toFile().bytes )
	output.flush();

	println("File inviato correttamente");
			
}
