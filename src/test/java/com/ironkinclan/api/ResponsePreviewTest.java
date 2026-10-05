package com.ironkinclan.api;

import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ResponsePreviewTest
{
	private static Response responseWith(String body)
	{
		return new Response.Builder()
			.request(new Request.Builder().url("https://ironkin.example.com/events/item-list").build())
			.protocol(Protocol.HTTP_1_1)
			.code(400)
			.message("Bad Request")
			.body(body == null ? null : ResponseBody.create(MediaType.get("application/json; charset=utf-8"), body))
			.build();
	}

	@Test
	public void of_shortBody_returnsItUnchanged()
	{
		assertEquals("{\"error\":\"Unknown boss\"}", ResponsePreview.of(responseWith("{\"error\":\"Unknown boss\"}")));
	}

	@Test
	public void of_multiLineBody_collapsesWhitespaceToOneLine()
	{
		assertEquals("{ \"error\": \"Unknown boss\" }", ResponsePreview.of(responseWith("{\n\t\"error\":   \"Unknown boss\"\r\n}\n")));
	}

	@Test
	public void of_longBody_isTruncated()
	{
		StringBuilder body = new StringBuilder();
		for (int i = 0; i < ResponsePreview.MAX_LENGTH + 100; i++)
		{
			body.append('x');
		}

		String preview = ResponsePreview.of(responseWith(body.toString()));

		assertEquals(body.substring(0, ResponsePreview.MAX_LENGTH) + "... (truncated)", preview);
	}

	@Test
	public void of_emptyBody_saysSo()
	{
		assertEquals("(empty)", ResponsePreview.of(responseWith("")));
	}

	@Test
	public void of_noBody_saysSo()
	{
		assertEquals("(no body)", ResponsePreview.of(responseWith(null)));
	}

	@Test
	public void of_leavesBodyReadableForTheCaller() throws IOException
	{
		Response response = responseWith("{\"balance\":5}");

		ResponsePreview.of(response);

		assertEquals("{\"balance\":5}", response.body().string());
	}
}
