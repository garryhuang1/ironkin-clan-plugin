package com.ironkinclan.manager;

public interface DiagnosticListener
{
	void onDiagnosticEvent(String text, boolean success);
}
