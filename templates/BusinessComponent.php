<?php namespace _namespace_;

class _business_component_ {

  /**
   * This is an hello world method.
   *
   * @author user <user@user.com>
   *
   * @param string $name say hello to this name.
   *
   * @frapid-url /hello
   * @frapid-method get
   *
   */
  public function sayHello( $name ) {
    return "Hello $name";
  }


}

?>
