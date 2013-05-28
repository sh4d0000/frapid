<?php

class MissingArgumentException extends Exception {
    
}

function call_user_func_named_array($method, array $arr = array()) {

    $ref = new ReflectionMethod($method[0], $method[1]);
    $params = array();

    $method_params = $ref->getParameters();

    foreach ($method_params as $p) {

        #var_dump( $p->name .' '. $p->getPosition() .' '. $p->isArray() );
        if( $p->getPosition() == 0 && $p->isArray()) {
          $params[] = $arr;
        } else {
           if (!$p->isOptional() and !isset($arr[$p->name])) {
              throw new MissingArgumentException("Missing parameter $p->name");
           } if (!isset($arr[$p->name])) {
              $params[] = $p->getDefaultValue();
           } else {
              $params[] = $arr[$p->name];
           }
        }
    }

    $class = new ReflectionClass($method[0]);
    return $ref->invokeArgs($class->newInstance(), $params);
}

class Action_Frontcontroller extends Frapi_Action implements Frapi_Action_Interface {

    protected $requiredParams = array();
    private $data = array();

    public function toArray() {
        return $this->data;
    }

    public function executeAction() {

        $frapid = new Frapid();
        $uri = $frapid->getUri();
        $httpMethod = $frapid->getHTTPMethod();

        $permission = $frapid->check_token();

        if ($permission == false) {
            throw new Frapi_Error('UNAUTHORIZED', 'Invalid access token', 401);
        }
        
        $component = $frapid->getComponentPath( $httpMethod.":".$uri);
        $params = array_merge( $this->getParams(), $component["params"]);
        $result = call_user_func_named_array($component["path"], $params);

        if (is_scalar($result)) {
            $result = array("data" => $result);
        }
        if (is_object($result)) {

            if (method_exists($result, 'toArray')) {
                $result = $result->toArray();
            }

            $result = (array) $result;
        }

        return $result;
    }

}

?>
