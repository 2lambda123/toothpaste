package qut.pm.spm.results;

public class CopyTask {

	public static void main(String[] args) throws Exception{
		if (args.length < 3 || args.length > 3) {
			System.out.println("Usage: CopyTask sourceFile stat targetFile");
		}
		String sourceFile = args[0];
		String stat = args[1];
		String targetFile = args[2];
		new TaskRewrites().copyTask(sourceFile, stat, targetFile);
		System.out.println("Copied " + stat + " from " + sourceFile + " into " + targetFile);
	}
	
}
