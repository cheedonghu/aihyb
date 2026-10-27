package run.aihyb.pluginaihyb;

import java.util.List;

public class PackageProblem extends RuntimeException {
    private final int status;
    private final List<String> errors;

    public PackageProblem(int status, String message) {
        super(message);
        this.status = status;
        this.errors = List.of(message);
    }

    public PackageProblem(int status, List<String> errors) {
        super("稿件包校验失败");
        this.status = status;
        this.errors = errors;
    }

    public int status() { return status; }
    public List<String> errors() { return errors; }
}
