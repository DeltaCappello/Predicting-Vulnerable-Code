import requests
import os
import getpass
import platform
from pydriller import RepositoryMining

class RepoMining:
    allCommit = []
    classMined = []

    def __init__(self, repo_url, commitHash):
        self.repo_url = repo_url
        self.commitHash = commitHash

    def exist(self, repo_url, commitHash):
        if not self.isEmptyUrl(self, repo_url):
            if self.isEmptyHash(self, commitHash):
                response = requests.get(repo_url)
                if response:
                    try:
                        self.allCommit = self.getAllCommit(self, repo_url)
                        return self.allCommit
                        #start mining from a specific commit got from user's choice
                    except ValueError:
                        print("Repo doesn't exists or not available")
            else:
                response = requests.get(str(repo_url) + "/commit/" + str(commitHash))
                if response:
                    try:
                        self.classMined = self.startMining(self, repo_url, commitHash)
                        return self.classMined
                    except:
                        print("ValueError: SHA for commit not defined")
                else:
                    print("commit doesn't exist")
        else:
            print("Repo Url is null or empty")
            return None


    def startMining(self, repo_url, commitHash):
        allClass = []
        cwd = self.setDefaultDir(self)
        for commit in RepositoryMining(repo_url, commitHash).traverse_commits():
            for mod in commit.modifications:
                if ".java" in mod.filename:
                    if commit.hash not in os.listdir():
                        os.mkdir(commit.hash)
                    #if exist, enter into the folder
                    os.chdir(commit.hash)
                    if mod.source_code_before != None:
                        javaFile = open(mod.filename, "w+")
                        javaFile.write(mod.source_code_before)
                        allClass.append(str(mod.filename))
                    os.chdir(cwd)
        javaFile.close()
        return allClass

    def setDefaultDir(self):
        cwd = self.setDefaultPath(self)
        os.chdir(cwd)
        if not os.path.isdir(str(cwd) + "Perseverance"):
            os.mkdir("Perseverance")
        else:
            os.chdir(str(cwd) + "Perseverance")
        return os.getcwd()

    def setDefaultPath(self):
        operatingSys = platform.system()
        user = getpass.getuser()
        directory = ""
        if operatingSys == "Windows":
            directory = "C:\\Users\\" + user + "\\Desktop\\"
            return directory
        elif operatingSys == "Darwin":
            directory = "/Users/" + user + "/Desktop/"
            return directory
        elif operatingSys == "Linux":
            directory = "/home/" + user + "/Scrivania"
            return directory

    def getAllCommit(self, repo_url):
        allCommit = []
        if not self.isEmptyUrl(self, repo_url):
            for commit in RepositoryMining(repo_url).traverse_commits():
                allCommit.append(commit.hash)
        return allCommit

    def isEmptyUrl(self, repo_url):
        if not str(repo_url) or repo_url is None:
            return True
        else:
            return False

    def isEmptyHash(self, commitHash):
        if not str(commitHash) or commitHash is None:
            return True
        else:
            return False