package com.levelup.palabre.flickrforpalabre.flickr.data;

public class Username
{
    private String _content;

    public String get_content ()
    {
        return _content;
    }

    public void set_content (String _content)
    {
        this._content = _content;
    }

    @Override
    public String toString()
    {
        return "ClassPojo [_content = "+_content+"]";
    }
}
