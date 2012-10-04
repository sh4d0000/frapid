<?php
class RoutesXmlFilter extends RecursiveFilterIterator {
   public function __construct($iterator) {
     parent::__construct($iterator);
   }

   public function accept() {
     return $this->hasChildren() || ($this->current()->isFile() && "routes.xml" == $this->getFilename());
   }

   public function __toString() {
     return $this->current()->getFilename();
   }
}

class Frapid {
  

    public function getUri()  {

        $uri = explode( '?',$_SERVER['REQUEST_URI']);
        $uri = explode( '.', $uri[0] );
        $uri = $uri[0];
        if( substr( $uri, -1) != '/' ) $uri = $uri.'/';

        return $uri;
    }

    public function getQueryString()  {

        $query = explode( '.',$_SERVER['QUERY_STRING']);
        return $query[0];
    }


    public function getRouteMap()  {

        $routeMap = array();
        $it = new RecursiveDirectoryIterator( CUSTOM_PATH . DIRECTORY_SEPARATOR . 'frapid' ); 
        $it = new RoutesXmlFilter($it);

        foreach (new RecursiveIteratorIterator( $it ) as $fileInfo) {
          echo '--->'.$fileInfo."\n";
           $string = file_get_contents( $fileInfo->getPathname() );
           $routesConfig = $this->xmlstr_to_array($string);
           $root = $routesConfig["root"];

           foreach ($routesConfig["routes"] as $route){
              $url = $route["url"];

              if( substr( $url, -1) != '/' ) $url = $url.'/';
              $routeMap[ $root . $url ] = $route["component"];
           }
 
        }

        var_dump($routeMap);
        return $routeMap;
    }


    public function getComponentPath( $uri ) {

        $routeMap = $this->getRouteMap();
        $comp_path = null;
        $params = null;

        if( isset( $routeMap[$uri] ) ) {
           $comp_path = $routeMap[$uri];
        } else { 

           foreach($routeMap as $uriRoute=>$path ) {
        
	      $params = array();
	      parse_str( $this->getQueryString(), $params );

              $uri_ = array_filter( explode('/', $uri ) );
              $uriRoute = array_filter( explode('/', $uriRoute ) );

   	      $length = count($uriRoute);
	      if( count($uri_) != $length ) {
	         continue;
	      }

              for( $i = 1; $i <= $length; $i++ ) {
        
		 $segment = $uri_[$i];
		 $segmentRoute = $uriRoute[$i];
		
                 if( $segmentRoute[0] == ':' ) {
		    $params[substr( $segmentRoute, 1 )] = $segment;
     		    if( $i == $length  ) {
		       $comp_path = $path;
		       break 2;
		    }
   		 } else if( $segment != $segmentRoute  ) {
		    break;
     		 } else if( $i == $length  ){
		    $comp_path = $path;
		    break 2;
                 }
              }

           }

        }

	$comp_path = explode( "#", $comp_path );
        return array( "path"=>$comp_path, "params"=>$params );
    
    }

    public function executeComponentMethodByUri( $uri )  {

        $compPath = $this->getComponentPath( $uri );
        $component = new $compPath[0];
        return $component->$compPath[1]();
    }



public function domnode_to_array($node) {
  $output = array();
  switch ($node->nodeType) {
   case XML_CDATA_SECTION_NODE:
   case XML_TEXT_NODE:
    $output = trim($node->textContent);
   break;
   case XML_ELEMENT_NODE:
    for ($i=0, $m=$node->childNodes->length; $i<$m; $i++) {
     $child = $node->childNodes->item($i);
     $v = $this->domnode_to_array($child);
     if(isset($child->tagName)) {
       $t = $child->tagName;
       if(!isset($output[$t])) {
        $output[$t] = array();
       }
       $output[$t][] = $v;
     }
     elseif($v) {
      $output = (string) $v;
     }
    }
    if(is_array($output)) {
     if($node->attributes->length) {
      $a = array();
      foreach($node->attributes as $attrName => $attrNode) {
       $a[$attrName] = (string) $attrNode->value;
      }
      $output['@attributes'] = $a;
     }
     foreach ($output as $t => $v) {
      if(is_array($v) && count($v)==1 && $t!='@attributes') {
       $output[$t] = $v[0];
      }
     }
    }
   break;
  }
  return $output;
}
public function xmlstr_to_array($xmlstr) {
  $doc = new DOMDocument();
  $doc->loadXML($xmlstr);
  return $this->domnode_to_array($doc->documentElement);
}


}
?>


