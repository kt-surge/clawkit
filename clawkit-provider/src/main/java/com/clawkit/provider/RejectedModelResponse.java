package com.clawkit.provider;

/** Opt-in diagnostics for received responses that cannot become executable ModelResponse objects.
 * Never included in exception messages or ordinary logs. Raw text is untrusted. */
public record RejectedModelResponse(String phase,String rawResponse,boolean truncated,TokenUsage usage) {
    public static RejectedModelResponse bounded(String phase,byte[] body,TokenUsage usage) {
        int limit=262144;
        String text=new String(body,0,Math.min(body.length,limit),java.nio.charset.StandardCharsets.UTF_8);
        return new RejectedModelResponse(phase,text,body.length>limit,usage);
    }
    @Override public String toString() { return "RejectedModelResponse[phase="+phase+",truncated="+truncated+",usage="+usage+"]"; }
    public static RejectedModelResponse bounded(String phase,String body,TokenUsage usage) {
        int limit=262144;
        return new RejectedModelResponse(phase,body.length()>limit ? body.substring(0,limit) : body,body.length()>limit,usage);
    }
}
