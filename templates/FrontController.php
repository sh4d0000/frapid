<?php

class _front_controller_ extends Frapi_Action implements Frapi_Action_Interface
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

        $path = $frapid->getComponentPath( $uri );
	$result = call_user_func( $path["path"] );
	
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

var_dump($result);
        return $result;
    }
}

?>
