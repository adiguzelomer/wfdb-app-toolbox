/* ===========================================================
 * WFDB Java : Interface to WFDB Applications.
 *              
 * ===========================================================
 *
 * (C) Copyright 2012, by Ikaro Silva
 *
 * Project Info:
 *    Code: http://code.google.com/p/wfdb-java/
 *    WFDB: http://www.physionet.org/physiotools/wfdb.shtml
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 *
 *
 * Original Author:  Ikaro Silva
 * Contributor(s):   Daniel J. Scott;
 *
 * Changes
 * -------
 * Check: http://code.google.com/p/wfdb-java/list
 */ 

/** 
 * @author Ikaro Silva
 *  @version 1.0
 *  @since 1.0
 */



package org.physionet.wfdb;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.physionet.wfdb.physiobank.PhysioNetRecord;


public class Wfdbexec {

	private String commandName;
	protected static String WFDB_JAVA_HOME;
	protected static String WFDB_PATH;
	private static String WFDB_NATIVE_BIN;
	private static String osArch;
	private static String osName;
	private static String fileSeparator;
	public static final String WFDB_JAVA_VERSION="Beta";
	private List<String> commandInput;
	protected static Map<String,String> env;
	protected static File EXECUTING_DIR=null;
	protected String[] arguments;
	private static String packageDir="";
	private int DoubleArrayListCapacity=0;
	private static Logger logger =
			Logger.getLogger(Wfdbexec.class.getName());

	public Wfdbexec(String commandName){
		logger.finest("\n\t***Setting exec commandName to: " + commandName);
		this.commandName=commandName;
		set_environment();
	}

	public void setArguments(String[] args){
		arguments=args;
	}

	protected void setExecName(String execName) {
		commandName = execName;
	}

	public void setExecutingDir(File dir){
		logger.finer("\n\t***Setting EXECUTING_DIR: " 
				+ dir);
		EXECUTING_DIR=dir;
	}
	private void gen_exec_arguments() {
		commandInput = new ArrayList<String>();
		commandInput.add(WFDB_NATIVE_BIN + commandName);
		logger.finest("\n\t***commandInput.add = "+WFDB_NATIVE_BIN + commandName);
		if(arguments != null){
			for(String i: arguments)
				commandInput.add(i);
		}
		//TODO: For the RDSAMP case:
		//ensure array capacity when user submits N0 and N
		//or (default) by querying the signal size
		//for now, have this happens at the MATLAB wrapper level

	}

	public synchronized ArrayList<String> execToStringList() {
		gen_exec_arguments();
		ArrayList<String> results= new ArrayList<String>();
		ProcessBuilder launcher = setLauncher();
		logger.fine("\n\t***Executing Launcher with commandInput : " + "\t" + commandInput);
		String line = null;
		try {
			logger.finer("\n\t***Starting exec process...");
			Process p = launcher.start();
			logger.finer("\n\t***Creating read buffer and waiting for exec process...");
			BufferedReader output = new BufferedReader(new InputStreamReader(
					p.getInputStream()));
			//p.waitFor();
			logger.finer("\n\t***Reading output.");
			while ((line = output.readLine()) != null){
				logger.finest("\n\t***Reading output: \n" + line);
				results.add(line);
			}
		} catch (Exception e) {
			System.err.println("error executing: " +
					commandName);
			e.printStackTrace();
			return null;
		} 
		return results;
	}


	public synchronized ArrayList<String> execWithStandardInput(String[] inputData) throws IOException {

		gen_exec_arguments();
		ProcessBuilder launcher = setLauncher();
		launcher.redirectErrorStream(true);
		Process process= null;
		int exitStatus = 1; 
		ArrayList<String> results=null;

		try {
			process = launcher.start();
			if (process != null) {
				OutputReader or= new OutputReader(process.getInputStream()) ;
				InputWriter iw= new	InputWriter(process.getOutputStream(), inputData);
				iw.start();	
				or.start();
				iw.join();
				or.join();
				results=or.getResults();
			}
			exitStatus=process.waitFor();
		} catch (IOException e) {
			System.err.println("Either couldn't read from the template file or couldn't write to the OutputStream.");
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		process.destroy();
		if(exitStatus != 0){
			System.err.println("Process exited with errors!! Error code = "
					+exitStatus);
			for(String tmp : results)
				System.err.println(tmp);
		}
		return results;

	}


	public synchronized ArrayList<String> execToStringList(String[] args) {
		setArguments(args);   
		return execToStringList();
	}

	public double[][] execToDoubleArray(String[] args) throws Exception {
		setArguments(args);   
		gen_exec_arguments();

		ArrayList<Double[]>  results= new ArrayList<Double[]>();
		if(DoubleArrayListCapacity>0){
			//Set capacity to ensure more efficiency
			results.ensureCapacity(DoubleArrayListCapacity);
		}
		double[][] data=null;
		int isTime=-1;//Index in case one of the columns is time as string

		ProcessBuilder launcher = setLauncher();
		try {
			Process p = launcher.start();
			BufferedReader output = new BufferedReader(new InputStreamReader(
					p.getInputStream()));
			String line;
			String[] tmpStr=null;
			Double[] tmpArr=null;
			char[] tmpCharArr=null;
			int colInd;
			int dataCheck=0;
			while ((line = output.readLine()) != null){
				tmpStr=line.trim().split("\\s+");
				tmpArr=new Double[tmpStr.length];
				//loop through columns
				for(colInd=0;colInd<tmpStr.length;colInd++){
					try{    
						tmpArr[colInd]= Double.valueOf(tmpStr[colInd]);
					}catch (NumberFormatException e){
						//Deal with cases that are not numbers 
						//but in an expected format
						if(tmpStr[colInd].equals("-")){
							//Dealing with NaN , so we need to convert 
							//WFDB Syntax "-" to Java's Double NaN
							tmpArr[colInd]=Double.NaN;	
						}else if((tmpStr[colInd].contains(":"))){
							//This column is likely a time column
							//for now, set values to NaN and remove column
							tmpArr[colInd]=Double.NaN;
							if(isTime<0){
								isTime=colInd;
							}
							dataCheck++;
						}else {
							//Attempt to convert single characters to integers
							try{
								tmpCharArr=tmpStr[colInd].toCharArray();
								tmpArr[colInd]= (double) tmpCharArr[0];
								dataCheck++;
							}catch(Exception e2) {
								System.err.println("Could not convert to double: " + line);
								throw(e2);
							}
						}
					}
				}

				if(results.isEmpty() && dataCheck==tmpStr.length){
					System.err.println("Error: Cannot convert to double: ");
					System.err.println(line);
					throw new NumberFormatException("Cannot convert");
				}else {
					results.add(tmpArr);
				}
			}

			//Wait to for exit value
			int exitValue = p.waitFor();
			if(exitValue != 0){
				throw new Exception("Command exited with error!");
			}
			//Convert data to Double Array
			int N=tmpStr.length;
			if(isTime>-1){
				N--;
			}
			//TODO: find a way to use .toArray in case of column deletion
			//data=new double[results.size()][N];
			//data=results.toArray(data); this should replace the loops below

			data=new double[results.size()][N];
			int index=0;
			if(isTime>-1) {
				for(int i=0;i<results.size();i++){
					Double[] tmpData=new Double[tmpStr.length];
					tmpData=results.get(i);
					for(int k=0;k<N;k++){				
						if(isTime > -1 && k != isTime)
							index =  (k>isTime) ? (k-1) :k;
							data[i][index]=tmpData[k];
					}
				}
			} else { //Optimized for case where there is no 
				//column deletion
				for(int i=0;i<results.size();i++){
					Double[] tmpData=new Double[tmpStr.length];
					tmpData=results.get(i);
					for(int k=0;k<N;k++){				
						data[i][k]=tmpData[k];
					}
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}   
		return data;
	}

	public synchronized void setWFDBPATH(String str){
		logger.finer("\n\t***Setting WFDB_PATH: " 
				+ str);
		WFDB_PATH=str;
	}

	private synchronized ProcessBuilder setLauncher(){
		ProcessBuilder launcher = new ProcessBuilder();
		launcher.redirectErrorStream(true);
		env = launcher.environment();
		if(osName.contains("macosx")){
			env.put("DYLD_LIBRARY_PATH",WFDB_NATIVE_BIN);
		}else{
			env.put("LD_LIBRARY_PATH",WFDB_NATIVE_BIN);
		}
		env.put("WFDBNOSORT","1");
		if(WFDB_PATH != null){
			env.put("WFDB_PATH",WFDB_PATH);
			logger.finer("\n\t***launcher configure with WFDB_PATH: " 
					+ WFDB_PATH);
		}
		launcher.environment().put("WFDBNOSORT","1");
		logger.finest("\n\t***Setting executing process with command and arguments: " + commandInput);
		launcher.command(commandInput);
		if(EXECUTING_DIR != null){
			launcher.directory(EXECUTING_DIR);
		}
		return launcher;
	}

	//Private Methods
	private synchronized static void set_environment() {
		osArch = System.getProperty("os.arch");
		fileSeparator=System.getProperty("file.separator");
		osName=System.getProperty("os.name");
		osName=osName.replace(" ","");
		osName=osName.toLowerCase();
		if(osName.startsWith("windows")){
			osName="windows"; //Treat all Windows versions the same for now
		}
		String[] tmpDir;
		String tmpStr=null;
		try {
			packageDir=URLDecoder.decode(
					Wfdbexec.class.getProtectionDomain().getCodeSource().getLocation().getPath()
					, "UTF-8");
			if(osName.startsWith("windows")){
				//On Windows systems, packageDir fileseparator 
				//do not match with that of the OS
				if(packageDir.startsWith("/"))
					packageDir=packageDir.substring(1);
				packageDir=packageDir.replace("/","\\");
			}
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		if(packageDir.endsWith(".jar")){
			if(osName.startsWith("windows")){
				tmpDir=packageDir.split(fileSeparator+fileSeparator);
			}else{
				tmpDir=packageDir.split(fileSeparator);
			}
			//In JAR package, remove jar directory to get root location
			tmpStr=fileSeparator+ tmpDir[tmpDir.length-1];
			WFDB_JAVA_HOME=packageDir.replace(tmpStr,"")+fileSeparator;
		} else{
			//Assuming source development in *UNIX
			WFDB_JAVA_HOME=packageDir.replace("/bin/","/mcode/");
		}

		//Set path to executables based on system/arch
		WFDB_NATIVE_BIN= WFDB_JAVA_HOME + "nativelibs" + fileSeparator + 
				osName.toLowerCase() + "-" + osArch.toLowerCase() 
				+ fileSeparator ;
	}


	public List<String> getEnvironment(){
		ArrayList<String> variables= new ArrayList<String>();
		variables.add("WFDB_JAVA_HOME= " + WFDB_JAVA_HOME);
		logger.finer("\n\t***WFDB_JAVA_HOME: " + WFDB_JAVA_HOME);

		variables.add("WFDB_NATIVE_BIN= " + WFDB_NATIVE_BIN);
		logger.finer("\n\t***WFDB_NATIVE_BINr: " + WFDB_NATIVE_BIN);

		variables.add("WFDB_JAVA_VERSION= "+ WFDB_JAVA_VERSION);
		logger.finer("\n\t***WFDB_JAVA_VERSION: " + WFDB_JAVA_VERSION);

		variables.add("WFDB Java Package Dir= "+ packageDir);
		logger.finer("\n\t***WFDB Java Package Dir: " + packageDir);

		if(WFDB_PATH != null){
			variables.add("WFDB PATH= "+ WFDB_PATH);
			logger.finer("\n\t***WFDB PATH: " + WFDB_PATH);
		}
		variables.add("EXECUTING_DIR= "+ EXECUTING_DIR);
		logger.finer("\n\t***Exec dir: " + EXECUTING_DIR);
		variables.add("osName= " + osName);
		logger.finer("\n\t***OS: " + osName);
		variables.add("osArch= " + osArch);
		logger.finer("\n\t***OS Arch: " + osArch);
		variables.add("OS Version= " + System.getProperty("os.version"));
		logger.finer("\n\t***OS Version: " + System.getProperty("os.version"));
		variables.add("JVM Version= " + System.getProperty("java.version"));
		logger.finer("\n\t***JVM Version: " + System.getProperty("java.version"));
		return variables;
	}

	public void printEnvironment(){
		for(String tmp : env.keySet()){
			if(tmp == null){
				System.out.println("Environment is null");
			}else{
				System.out.println(tmp + " = " + env.get(tmp));
			}
		}
	}
	public void setDoubleArrayListCapacity(int capacity){
		DoubleArrayListCapacity=capacity;
	}

	public void setLogLevel(int level){
		//Include this method to allow for debugging within MATLAB instances
		Level debugLevel;
		switch (level) {
		case 0:
			debugLevel=Level.OFF;break;
		case 1:
			debugLevel=Level.SEVERE;break;
		case 2:
			debugLevel=Level.WARNING;break;
		case 3: 
			debugLevel=Level.INFO;break;
		case 4:
			debugLevel=Level.FINEST;break;
		case 5:
			debugLevel=Level.ALL;break;
		default :
			debugLevel=Level.OFF;break;
		}

		Handler[] handlers =
				Logger.getLogger( "" ).getHandlers();
		for ( int index = 0; index < handlers.length; index++ ) {
			handlers[index].setLevel( debugLevel );
		}

		Logger.getLogger("org.physionet").setLevel(debugLevel);
	}

	public static void main(String[] args) throws Exception {

		Level debugLevel = Level.FINEST;//use for debugging Level.FINEST;
		if(debugLevel != null){
			Handler[] handlers =
					Logger.getLogger( "" ).getHandlers();
			for ( int index = 0; index < handlers.length; index++ ) {
				handlers[index].setLevel( debugLevel );
			}
			Logger.getLogger("org.physionet.wfdb.Wfdbexec").setLevel(debugLevel);
		}

		Wfdbexec exec = new Wfdbexec(args[0]);
		double[][] data = exec.execToDoubleArray(Arrays.copyOfRange(args,1,args.length));
		for(int row=0;row<data.length;row++){
			for(int col=0;col<data[0].length;col++){
				System.out.print(data[row][col] +" ");
			}
			System.out.println("");
		}

	}

} 