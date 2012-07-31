package org.epics.directory.service;

@SuppressWarnings("serial")
public class UnableToGetDataException extends Exception 
{
	public
    UnableToGetDataException()
    {
        super();
    }

	public UnableToGetDataException(String _message) {
		super(_message);
	}
}



