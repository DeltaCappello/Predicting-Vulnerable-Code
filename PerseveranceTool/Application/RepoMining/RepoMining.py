import requests
import os
import getpass
import platform
from pydriller import RepositoryMining

class RepoMining:
    allCommit = []

    def __init__(self, repo_url, commitHash):
        self.repo_url = repo_url
        self.commitHash = commitHash

    def exist(self, repo_url, commitHash):
        if self.isEmptyHash():
            response = requests.get(repo_url)
            if response:
                try:
                    self.allCommit = self.getAllCommit(repo_url, None)
                    #start mining from a specific commit
                except ValueError:
                    print("Repo doesn't exists or not available")
        else:
            response1 = requests.get(repo_url + "/commit/" + commitHash)
            if response1:
                try:
                    self.startMining(repo_url, commitHash)
                except:
                    print("ValueError: SHA for commit not defined")
            else:
                print("commit doesn't exist")


    def startMining(self, repo_url, commitHash):
        cwd = self.setDefaultDir()
        os.chdir(cwd)
        os.mkdir("Perseverance")
        for commit in RepositoryMining(repo_url, commitHash).traverse_commits():
            for mod in commit.modifications:
                if ".java" in mod.filename:
                    if commit.hash() not in os.listdir():
                        os.mkdir(commit.hash())
                    #if exist, enter into the folder
                    os.chdir(commit.hash())
                    if mod.source_code_before != None:
                        javaFile = open(mod.filename, "w+")
                        javaFile.write(mod.source_code_before)
                    os.chdir(cwd)
        javaFile.close()

    def setDefaultDir(self):
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
        if not str(commitHash) or commitHash == None:
            return True
        else:
            return False