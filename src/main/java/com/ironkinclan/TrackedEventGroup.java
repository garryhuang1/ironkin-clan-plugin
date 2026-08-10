package com.ironkinclan;

import java.util.List;

class TrackedEventGroup
{
	final String eventId;
	final List<TrackedItem> items;

	TrackedEventGroup(String eventId, List<TrackedItem> items)
	{
		this.eventId = eventId;
		this.items = items;
	}
}
