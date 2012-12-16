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

    public function getUri() {

        $uri = explode('?', $_SERVER['REQUEST_URI']);
        $uri = explode('.', $uri[0]);
        $uri = $uri[0];
        if (substr($uri, -1) != '/')
            $uri = $uri . '/';

        return strtolower($uri);
    }

    public function getHTTPMethod() {
        return strtolower($_SERVER['REQUEST_METHOD']);
    }

    public function getQueryString() {

        $uri = explode('?', $_SERVER['REQUEST_URI']);
        
        if( !isset( $uri[1] ) ) {
           return "";
        }

        return $uri[1];

    }

    public function getRouteMap() {

        $routeMap = array();
        $it = new RecursiveDirectoryIterator(CUSTOM_PATH . DIRECTORY_SEPARATOR . 'frapid');
        $it = new RoutesXmlFilter($it);

        foreach (new RecursiveIteratorIterator($it) as $fileInfo) {

            $string = file_get_contents($fileInfo->getPathname());
            $routesConfig = $this->xmlstr_to_array($string);
            $root = $routesConfig["root"];
            
            if (substr($root, 0, 1) != '/') {
                $root = '/' . $root;
            }
            
            $namespace = substr($root, 1) . '\\';
            $toIterate = $routesConfig["routes"];

            if (isset($toIterate["route"][0])) {
                $toIterate = $toIterate["route"];
            }

            foreach ($toIterate as $route) {

                $url = $route["url"];

                $methods = isset($route["method"]) ? $route["method"] : "get";
                $methods = array_filter(explode(',', $methods));


                if (substr($url, -1) != '/') {
                    $url = $url . '/';
                }

                foreach ($methods as $method) {
                    $method = trim($method);
                    $routeMap[strtolower($method . ":" . $root . $url)] = $namespace . $route["component"];
                }
            }
            
        }

        return $routeMap;
    }

    public function getComponentPath($uri) {

        $routeMap = $this->getRouteMap();
        $comp_path = null;
        $params = array();
        parse_str($this->getQueryString(), $params);

        if (isset($routeMap[$uri])) {
            $comp_path = $routeMap[$uri];
        } else {
            foreach ($routeMap as $uriRoute => $path) {

                $uri_ = array_filter(explode('/', $uri));
                $uriRoute = array_filter(explode('/', $uriRoute));
                
                $length = count($uriRoute);
                if (count($uri_) != $length) {
                    continue;
                }

                for ($i = 0; $i < $length; $i++) {
                    // TODO controllo index out of bound: gestione mancanza component per quell'uri
                    $segment = $uri_[$i];
                    $segmentRoute = $uriRoute[$i];
                    
                    if ($segmentRoute[0] == ':') {
                        $params[substr($segmentRoute, 1)] = $segment;
                        if ($i == ($length - 1) ) {
                            $comp_path = $path;
                            break 2;
                        }
                    } else if ($segment != $segmentRoute) {
                        break;
                    } else if ($i == $length) {
                        $comp_path = $path;
                        break 2;
                    }
                }
            }
        }

        $comp_path = explode("#", $comp_path);
        return array("path" => $comp_path, "params" => $params);
    }

    public function check_token() {

        $config = $this->get_frapi_config();
        if ($config['apistore-mode'] == "false") {
            return true;
        }

        $uri = $this->getUri();
        $component = $this->getComponentPath($uri);

        $uri = array_filter(explode('/', $uri));

        $params = array();
        parse_str($this->getQueryString(), $params);

        if (!isset($params['token'])) {
            return false;
        }

        $api_name = $uri[1];
        $token = $params['token'];

        $con = mysql_connect($config['database']['url'], $config['database']['username'], $config['database']['password']);
        mysql_select_db('drupal7');

        $query = sprintf("SELECT * FROM drupal7.api a, drupal7.api_manager_product_user t WHERE name ='%s' and a.sku = t.sku and token = '%s'", mysql_real_escape_string($api_name), mysql_real_escape_string($token));
        $result = mysql_query($query);
        $no_of_rows = mysql_num_rows($result);

        if ($no_of_rows > 0) {
            return true;
        } else {
            return false;
        }

        mysql_free_result($result);
        mysql_close();
    }

    public function get_app_config($api_name) {

        $string = file_get_contents(CUSTOM_PATH . DIRECTORY_SEPARATOR . 'frapid' . DIRECTORY_SEPARATOR . $api_name . DIRECTORY_SEPARATOR . 'config.xml');
        $config = $this->xmlstr_to_array($string);
        return $config;
    }

    public function get_frapi_config() {

        $string = file_get_contents(CUSTOM_PATH . DIRECTORY_SEPARATOR . 'frapid' . DIRECTORY_SEPARATOR . 'frapi_conf.xml');
        $config = $this->xmlstr_to_array($string);
        return $config;
    }

    public function executeComponentMethodByUri($uri) {

        $compPath = $this->getComponentPath($uri);
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
                for ($i = 0, $m = $node->childNodes->length; $i < $m; $i++) {
                    $child = $node->childNodes->item($i);
                    $v = $this->domnode_to_array($child);
                    if (isset($child->tagName)) {
                        $t = $child->tagName;
                        if (!isset($output[$t])) {
                            $output[$t] = array();
                        }
                        $output[$t][] = $v;
                    } elseif ($v) {
                        $output = (string) $v;
                    }
                }
                if (is_array($output)) {
                    if ($node->attributes->length) {
                        foreach ($node->attributes as $attrName => $attrNode) {
                            $output[$attrName] = (string) $attrNode->value;
                        }
                    }
                    foreach ($output as $t => $v) {
                        if (is_array($v) && count($v) == 1 && $t != '@attributes') {
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

