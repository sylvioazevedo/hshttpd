package hshttpd.controller

import javax.activation.MimetypesFileTypeMap

class Util {

    static findContentType(File file) {

        if(file.name.endsWith("css")) {
            return "text/css"
        }

        if(file.name.endsWith("js")) {
            return "application/javascript"
        }

        if(file.name.endsWith("less")) {
            return "plain/text"
        }

        if(file.name.endsWith("json")) {
            return "application/json"
        }

        if(file.name.endsWith("xml")) {
            return "application/xml"
        }

        if(file.name.endsWith("gif")) {
            return "image/gif"
        }

        if(file.name.endsWith("png")) {
            return "image/png"
        }

        if(file.name.endsWith("txt")) {
            return "text/plain"
        }

        // if not found return by file type
        new MimetypesFileTypeMap().getContentType(file)
    }
}
