<?php
class MissingArgumentException extends Exception {
}

function call_user_func_named_array($method, $arr=array()){
  var_dump($method);
  $ref = new ReflectionMethod($method[0], $method[1]);
  $params = array();
  foreach( $ref->getParameters() as $p ){
    if (!$p->isOptional() and !isset($arr[$p->name])) throw new MissingArgumentException("Missing parameter $p->name");
    if (!isset($arr[$p->name])) $params[] = $p->getDefaultValue();
    else $params[] = $arr[$p->name];
  }
  
  $class = new ReflectionClass( $method[0]);
  return $ref->invokeArgs( $class->newInstance(), $params );
}

class Action_Frontcontroller extends Frapi_Action implements Frapi_Action_Interface
{

    protected $requiredParams = array();
    private $data = array();

    public function toArray()
    {
        return $this->data;
    }


    public function executeAction()  {

        $frapid = new Frapid();
        $uri = $frapid->getUri();
        echo "uri\n";
var_dump($uri);
        $component = $frapid->getComponentPath( $uri );
        echo "component\n";
var_dump($component);
	$result = call_user_func_named_array( $component["path"], $component["params"] );
	
	if( is_scalar($result) ) {
	   echo 'scalar';
	   $result = array( "data"=>$result );
	}
	if( is_object($result) ) {
	   echo 'object';

	   if( method_exists( $result, 'toArray') ) {
	   echo 'object ext';
	      $result = $result->toArray();	
	   }

	   $result = (array) $result;

	}

        return $result;
    }
}

?>
